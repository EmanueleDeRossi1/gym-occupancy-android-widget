import { OPERATORS } from './config.js';
import {
  transformFitnessFirst,
  transformFitX,
  transformRSG,
  transformBestFit,
  transformCleverFit
} from './transformers/gyms.js';

import {
  transformFitnessFirstOccupancy,
  transformFitXOccupancy,
  transformRSGOccupancy,
  transformBestFitOccupancy,
  transformCleverFitOccupancy,
  nullFutureOccupancy
} from './transformers/occupancy.js';

const LIST_TRANSFORMERS = {
  'fitness-first': transformFitnessFirst,
  'fitx': transformFitX,
  'rsg-group': transformRSG,
  'bestfit': transformBestFit,
  'clever-fit': transformCleverFit
};


const OCCUPANCY_TRANSFORMERS = {
  'fitness-first': transformFitnessFirstOccupancy,
  'fitx': transformFitXOccupancy,
  'rsg-group': transformRSGOccupancy,
  'bestfit': transformBestFitOccupancy,
  'clever-fit': transformCleverFitOccupancy
};

async function fetchOperatorGyms(operator) {

  try {
    const response = await fetch(operator.gymListUrl, {
      headers: operator.gymListHeaders
    });

    if (!response.ok) {
      console.error(`Failed to fetch gym list from ${operator.name}: ${response.status} ${response.statusText}`);
      return [];
    }

  const rawData = await response.json();
  const transformer = LIST_TRANSFORMERS[operator.id];
  const gymList = transformer(rawData);

  return gymList;
  
} catch (error) {
  console.error(`Error in fetchOperatorGyms for ${operator.name}:`, error);
  return [];
  }
}

// Fitness first has a pagination limit
// we have to iterate through all pages to fetch all gyms
async function fetchFitnessFirstGyms(operator) {

  const allFitnessFirstRawData = [];
  let url = operator.gymListUrl;
  const MAX_SKIP_ATTEMPTS = 5;

  // collect all data
  while (url) {
    try {
      const response = await fetch(url, {
        headers: operator.gymListHeaders
      });

      if (!response.ok) {
        console.warn(`Failed to fetch from ${url}: ${response.status} ${response.statusText}`);

        // Try to skip past the bad range with small increments
        let skipAttempts = 0;
        let skipUrl = url;
        const currentOffset = extractOffsetFromUrl(url);

        while (skipAttempts < MAX_SKIP_ATTEMPTS) {
          skipAttempts++;
          const nextOffset = currentOffset + (10 * skipAttempts);
          skipUrl = `${operator.gymListUrl}?page[offset]=${nextOffset}&page[limit]=50`;

          console.log(`Skipping range... attempt ${skipAttempts}: trying offset ${nextOffset}`);

          const skipResponse = await fetch(skipUrl, { headers: operator.gymListHeaders });
          if (skipResponse.ok) {
            console.log(`Found working offset: ${nextOffset}`);
            url = skipUrl;
            break;
          }
        }

        if (skipAttempts >= MAX_SKIP_ATTEMPTS) {
          console.log(`Unable to skip past the bad range after ${MAX_SKIP_ATTEMPTS} attempts. Ending pagination.`);
          url = null;
        }
        continue;
      }

      const pageData = await response.json();
      url = pageData.links?.next?.href || null;

      allFitnessFirstRawData.push(...pageData.data);

      } catch (error) {
          console.error(`Error fetching gyms for operator ${operator.name}:`, error);
          url = null;
      }
    }

  const transformer = LIST_TRANSFORMERS[operator.id];
  const gymList = transformer(allFitnessFirstRawData);

  return gymList;

}

function extractOffsetFromUrl(url) {
  const match = url.match(/page%5Boffset%5D=(\d+)|page\[offset\]=(\d+)/);
  return match ? parseInt(match[1] || match[2]) : 0;
}


async function fetchAllGyms(env) {
  const allGyms = [];

  for (const operator of OPERATORS) {
    try {
        let gyms;

        if (operator.id === 'fitness-first') {
          gyms = await fetchFitnessFirstGyms(operator);
        } else {
          gyms = await fetchOperatorGyms(operator);
        }

      allGyms.push(...gyms);
      } catch (error) {
        console.error(`Error fetching gyms for operator ${operator.name}:`, error);
      }
  }

  const dataWithTimestamp = {
    gyms: allGyms,
    lastUpdated: new Date().toISOString(),
    totalCount: allGyms.length
  };

  await env.GYM_DATA.put('gym-list', JSON.stringify(dataWithTimestamp));

  return allGyms;
}

async function recordOccupancyHistory(env, gyms) {
  const today = new Date();
  const day = today.toISOString().split('T')[0];

  let successCount = 0;
  let errorCount = 0;

  const BATCH_SIZE = 10;
  for (let i = 0; i < gyms.length; i += BATCH_SIZE) {
    const batch = gyms.slice(i, i + BATCH_SIZE);
    await Promise.all(batch.map(async gym => {
      const operator = OPERATORS.find(o => o.id === gym.operatorId);
      if (!operator) return;

      try {
        const url = operator.occupancyUrl.replace('{gymId}', gym.gymId);
        const response = await fetch(url, { headers: operator.occupancyHeaders });
        if (!response.ok) {
          errorCount++;
          console.error(`[occupancy] HTTP ${response.status} for gym ${gym.gymId} (${gym.operatorId}): ${url}`);
          return;
        }

        const rawData = await response.json();
        const transformer = OCCUPANCY_TRANSFORMERS[gym.operatorId];
        const slots = transformer(rawData);

        const stmt = env.gym_occupancy_history.prepare(
          `INSERT OR REPLACE INTO occupancy_history (gym_id, location, operator_id, day, hour, occupancy)
           VALUES (?, ?, ?, ?, ?, ?)`
        );
        await env.gym_occupancy_history.batch(
          slots.map(slot => stmt.bind(gym.gymId, gym.location, gym.operatorId, day, slot.startTime, slot.occupancy))
        );
        successCount++;
      } catch (error) {
        errorCount++;
        console.error(`[occupancy] failed for gym ${gym.gymId} (${gym.operatorId}):`, error?.message ?? error);
      }
    }));
  }

  return { successCount, errorCount };
}

// All runs fire at 21:xx UTC (= 23:xx Berlin in summer, 22:xx in winter),
// before local midnight while the operator forecast is still populated.
const OPERATOR_BY_CRON_MINUTE = {
  0:  'fitness-first',  // 21:00 UTC
  12: 'fitx',           // 21:12 UTC
  24: 'rsg-group',      // 21:24 UTC
  36: 'bestfit',        // 21:36 UTC
  48: 'clever-fit'      // 21:48 UTC
};

async function runScheduledJob(env, scheduledTime, operatorOverride) {
  const date = scheduledTime ? new Date(scheduledTime) : new Date();
  const hour = date.getUTCHours();
  const minute = date.getUTCMinutes();

  let operatorId = operatorOverride;
  if (!operatorId) {
    operatorId = OPERATOR_BY_CRON_MINUTE[minute];
  }

  if (hour === 21 && minute === 0) {
    const gymList = await fetchAllGyms(env);
    console.log(`Fetched and stored ${gymList.length} gyms`);
  }

  const gymData = await env.GYM_DATA.get('gym-list');
  if (!gymData) return;
  const { gyms } = JSON.parse(gymData);
  const filtered = operatorId ? gyms.filter(g => g.operatorId === operatorId) : gyms;

  console.log(`[occupancy] starting — ${filtered.length} ${operatorId ?? 'all'} gyms`);
  const { successCount, errorCount } = await recordOccupancyHistory(env, filtered);
  console.log(`[occupancy] done — success: ${successCount}, errors: ${errorCount}`);
}


export default {

  async fetch(request, env, ctx) {

    const url = new URL(request.url);

    if (url.pathname === "/gyms") {
      const gymData = await env.GYM_DATA.get('gym-list')

      if (!gymData) {
        return new Response('No gym data available', { status: 404 });
      }

      return new Response(gymData, {
        headers: {
           'content-type': 'application/json',
           'cache-control': 'public, max-age=3600' // Cache for 1 hour
          }
      });
    }

    else if (url.pathname.endsWith('/occupancy')) {
      const operatorId = url.pathname.split('/')[1];
      const gymId = url.pathname.split('/')[2];

      for (const operator of OPERATORS) {
        if (operator.id === operatorId) {
          const operatorOccupancyUrl = operator.occupancyUrl.replace('{gymId}', gymId)
          try {
            const response = await fetch(operatorOccupancyUrl, {
              method: 'GET',
              headers: operator.occupancyHeaders
            });
            const rawData = await response.json();

            const transformer = OCCUPANCY_TRANSFORMERS[operatorId];
            const transformedData = nullFutureOccupancy(transformer(rawData));

            return new Response(JSON.stringify(transformedData), {
                  headers: { 'content-type': 'application/json' }
            })
              
          } catch(error) {
            console.error(error);
            return new Response('Unknown error fetching occupancy', { status: 500 })
          }
        }
      }
    }

    if (url.pathname === '/trigger') {
      const secret = request.headers.get('X-Trigger-Secret');
      if (!env.TRIGGER_SECRET || secret !== env.TRIGGER_SECRET) {
        return new Response('Unauthorized', { status: 401 });
      }
      const operatorId = url.searchParams.get('operator');
      if (url.searchParams.get('refresh-gyms') === 'true') {
        ctx.waitUntil(fetchAllGyms(env));
      } else {
        ctx.waitUntil(runScheduledJob(env, null, operatorId));
      }
      return new Response('OK');
    }

    return new Response('Not found', { status: 404 })
  },

  async scheduled(event, env, ctx) {
    ctx.waitUntil(runScheduledJob(env, event.scheduledTime));
  }
}