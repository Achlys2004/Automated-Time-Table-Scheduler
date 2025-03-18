package com.university.scheduler.controller;

import com.university.scheduler.model.TimetableEntry;
import com.university.scheduler.service.TimetableService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/timetable")
public class TimetableController {
    private final TimetableService service;

    public TimetableController(TimetableService service) {
        this.service = service;
    }

    @GetMapping
    public List<TimetableEntry> getTimetable() {
        return service.getTimetable();
    }

    @PostMapping("/generate")
    public List<TimetableEntry> generateSchedule(@RequestBody Map<String, Integer> subjectSessions) {
        return service.generateSchedule(subjectSessions);
    }
}
