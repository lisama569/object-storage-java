package com.github.lisama569.objectstoragejava.web;

import com.github.lisama569.objectstoragejava.dto.GarbageCollectionReport;
import com.github.lisama569.objectstoragejava.service.GarbageCollectorService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Maintenance entry points (garbage collection). */
@RestController
@RequestMapping("/api/maintenance")
public class MaintenanceController {

    private final GarbageCollectorService garbageCollector;

    public MaintenanceController(GarbageCollectorService garbageCollector) {
        this.garbageCollector = garbageCollector;
    }

    /** Run one non-blocking reclaim pass over unreferenced blobs. */
    @PostMapping("/gc")
    public GarbageCollectionReport collectGarbage() {
        return garbageCollector.collect();
    }
}
