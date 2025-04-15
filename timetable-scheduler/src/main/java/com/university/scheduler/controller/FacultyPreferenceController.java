package com.university.scheduler.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
import com.university.scheduler.model.FacultyPreference;
import com.university.scheduler.repository.FacultyPreferenceRepository;

@RestController
@RequestMapping("/api/faculty")
public class FacultyPreferenceController {

    @Autowired
    private FacultyPreferenceRepository facultyPreferenceRepository;
    
    @PostMapping("/preferences")
    public ResponseEntity<?> addFacultyPreference(@RequestBody FacultyPreference preference) {
        // Validate all required fields
        if (preference.getFaculty() == null || preference.getFaculty().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                "status", "error",
                "message", "Faculty name is required"
            ));
        }

        if (preference.getPreferredDays() == null || preference.getPreferredDays().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                "status", "error",
                "message", "At least one preferred day is required"
            ));
        }

        if (preference.getPreferredTime() == null || preference.getPreferredTime().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                "status", "error",
                "message", "At least one preferred time slot is required"
            ));
        }

        try {
            // Save preference to database
            FacultyPreference saved = facultyPreferenceRepository.save(preference);
            
            return ResponseEntity.ok().body(Map.of(
                "status", "success",
                "message", "Faculty preference added successfully",
                "data", saved
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                "status", "error",
                "message", "Failed to save faculty preference: " + e.getMessage()
            ));
        }
    }

    @GetMapping("/preferences/{faculty}")
    public ResponseEntity<?> getFacultyPreferences(@PathVariable String faculty) {
        try {
            return ResponseEntity.ok().body(Map.of(
                "status", "success",
                "message", "Faculty preferences retrieved successfully",
                "data", null 
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                "status", "error",
                "message", "Failed to retrieve faculty preferences: " + e.getMessage()
            ));
        }
    }

    @GetMapping("/preferences")
    public ResponseEntity<?> getAllPreferences() {
        try {
            List<FacultyPreference> preferences = facultyPreferenceRepository.findAll();
            return ResponseEntity.ok().body(preferences);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                "status", "error",
                "message", "Failed to retrieve faculty preferences: " + e.getMessage()
            ));
        }
    }
}

