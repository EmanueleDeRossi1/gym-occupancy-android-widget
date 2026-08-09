/**
 * Scrape FitX website predictions for a given gym.
 * 
 * The FitX website embeds a `data-visitordata` attribute containing
 * a 7-day forecast array of hourly percentage values.
 */

// Known overrides for single-location cities or renamed studio slugs on fitx.de
const SLUG_OVERRIDES = {
    'dorsten': 'dorsten-mitte',
    'grevenbroich': 'grevenbroich-stadt',
    'herten': 'herten-stadt',
    'hilden': 'hilden-stadt',
    'viersen': 'viersen-stadt',
    'unna': 'unna-stadt',
    'ulm': 'ulm-stadt',
    'arnsberg': 'arnsberg-huesten',
    'aachen-mitte': 'aachen-europaplatz',
    'berlin-kudamm': 'berlin-wilmersdorf',
    'dortmund-phoenix-see': 'dortmund-hoerde'
};

/**
 * Derive candidate fitx.de website slugs from the API studio name.
 */
function getCandidateSlugs(studioName) {
    let clean = studioName
        .replace(/^FitX\s*[-]?\s*/i, '')
        .toLowerCase()
        .replace(/ä/g, 'ae')
        .replace(/ö/g, 'oe')
        .replace(/ü/g, 'ue')
        .replace(/ß/g, 'ss')
        .replace(/['.]/g, '')
        .replace(/[^a-z0-9\s-]/g, '')
        .trim();

    const primary = clean.replace(/\s+/g, '-');
    const cityOnly = primary.split('-')[0];

    const candidates = [];

    // Check explicit overrides first
    if (SLUG_OVERRIDES[primary]) {
        candidates.push(SLUG_OVERRIDES[primary]);
    }
    if (SLUG_OVERRIDES[cityOnly]) {
        candidates.push(SLUG_OVERRIDES[cityOnly]);
    }

    candidates.push(primary);
    candidates.push(`${primary}-mitte`);
    candidates.push(`${primary}-stadt`);
    candidates.push(`${primary}-stadtmitte`);
    candidates.push(`${cityOnly}-mitte`);
    candidates.push(`${cityOnly}-stadt`);
    candidates.push(`${cityOnly}-stadtmitte`);

    return Array.from(new Set(candidates));
}

/**
 * Helper to fetch a URL preserving User-Agent across redirects.
 */
async function fetchWithHeaders(url, maxRedirects = 3) {
    const headers = { 'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)' };
    let currentUrl = url;

    for (let i = 0; i < maxRedirects; i++) {
        const response = await fetch(currentUrl, { headers, redirect: 'manual' });

        if (response.status >= 300 && response.status < 400) {
            const location = response.headers.get('location');
            if (!location) return null;

            // Don't follow redirects to the main studios overview list page
            if (location.endsWith('/fitnessstudios') || location.endsWith('/fitnessstudios/')) {
                return null;
            }

            currentUrl = location.startsWith('http') ? location : `https://www.fitx.de${location}`;
            continue;
        }

        if (!response.ok) return null;
        return await response.text();
    }

    return null;
}

/**
 * Fetch the 24-hour prediction array for today from the FitX website.
 * Returns an array of 24 integers (percentage values), or null on failure.
 */
export async function fetchFitXPredictions(studioName) {
    const candidates = getCandidateSlugs(studioName);

    for (const slug of candidates) {
        for (const prefix of ['/fitnessstudios/', '/fitnessstudio/']) {
            const url = `https://www.fitx.de${prefix}${slug}`;
            try {
                const html = await fetchWithHeaders(url);
                if (!html) continue;

                const match = html.match(/data-visitordata="([^"]+)"/);
                if (!match) continue;

                const raw = match[1].replace(/&quot;/g, '"');
                const weeklyData = JSON.parse(raw);

                if (!Array.isArray(weeklyData) || weeklyData.length !== 7) continue;

                // JS getDay(): 0 = Sun, 1 = Mon; FitX array: 0 = Mon ... 6 = Sun
                const now = new Date();
                const dayIdx = (now.getDay() + 6) % 7;
                const todayPredictions = weeklyData[dayIdx];

                if (!Array.isArray(todayPredictions) || todayPredictions.length === 0) continue;

                // Map/pad prediction slots to a standard 24-hour array (0..23)
                // If a studio has fewer than 24 slots (e.g. 22), align from start hour (e.g. 06:00 to 24:00)
                const full24 = new Array(24).fill(0);
                const offset = 24 - todayPredictions.length;
                for (let h = 0; h < todayPredictions.length; h++) {
                    full24[offset + h] = todayPredictions[h];
                }

                return full24;
            } catch (e) {
                // Continue trying candidate slugs
            }
        }
    }

    return null;
}
