function extractTimeFromISO(isoString) {
    const timeMatch = isoString.match(/T(\d{2}:\d{2}:\d{2})/);
    return timeMatch ? timeMatch[1] : null;
}

// For future hours (occupancy not known yet) we return null.
export function nullFutureOccupancy(slots) {
    const currentIndex = slots.findIndex(s => s.isCurrent);
    if (currentIndex === -1) return slots;
    return slots.map((slot, i) =>
        i > currentIndex ? { ...slot, occupancy: null } : slot
    );
}

export function transformFitnessFirstOccupancy(rawData) {
    if (!rawData || !rawData.data || !rawData.data.items) return [];
    
    return rawData.data.items.map(item => ({
        startTime: item.startTime,
        endTime: item.endTime,
        occupancy: item.percentage,
        isCurrent: item.isCurrent
    }));
}

export function transformFitXOccupancy(rawData) {
    if (!rawData || !rawData.items) return [];
    
    return rawData.items.map(item => ({
        startTime: item.startTime,
        endTime: item.endTime,
        occupancy: item.percentage,
        isCurrent: item.isCurrent
    }));
}

export function transformRSGOccupancy(rawData) {
    if (!Array.isArray(rawData)) return [];
    
    return rawData.map(item => ({
        startTime: extractTimeFromISO(item.startTime),
        endTime: extractTimeFromISO(item.endTime),
        occupancy: item.percentage,
        isCurrent: item.current
    }));
}

export function transformBestFitOccupancy(rawData) {
    if (!rawData || !rawData.items) return [];
    
    return rawData.items.map(item => ({
        startTime: item.startTime,
        endTime: item.endTime,
        occupancy: item.percentage,
        isCurrent: item.isCurrent
    }));
}

export function transformCleverFitOccupancy(rawData) {
    if (!rawData || !rawData.items) return [];
    
    return rawData.items.map(item => ({
        startTime: item.startTime,
        endTime: item.endTime,
        occupancy: item.percentage,
        isCurrent: item.isCurrent
    }));
}
