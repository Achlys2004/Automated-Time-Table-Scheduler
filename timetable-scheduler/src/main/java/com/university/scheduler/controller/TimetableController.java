package com.university.scheduler.controller;

import com.university.scheduler.model.TimetableEntry;
import com.university.scheduler.model.TimetableRequest;
import com.university.scheduler.service.TimetableService;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.*;
import com.university.scheduler.repository.FacultyPreferenceRepository; // Ensure this is the correct package
import com.university.scheduler.model.FacultyPreference; // Ensure this is the correct package
import com.university.scheduler.model.Subject; // Ensure this is the correct package

@RestController
@RequestMapping("/api/timetable")
public class TimetableController {

    @Autowired
    private TimetableService timetableService;

    @Autowired
    private FacultyPreferenceRepository facultyPreferenceRepository;

    public TimetableController(TimetableService timetableService) {
        this.timetableService = timetableService;
    }

    @PostMapping("/generate")
    public ResponseEntity<?> generateTimetable(@RequestBody TimetableRequest request) {
        // Validation
        if (request.getSubjects() == null || request.getSubjects().isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("Invalid request: Subjects list is required");
        }

        try {
            // Get faculty preferences for each subject
            Map<String, FacultyPreference> facultyPreferences = new HashMap<>();
            for (Subject subject : request.getSubjects()) {
                facultyPreferenceRepository.findByFaculty(subject.getFaculty())
                        .ifPresent(pref -> facultyPreferences.put(subject.getFaculty(), pref));
            }

            // Consider preferences while generating timetable
            List<String> conflicts = new ArrayList<>();
            for (Map.Entry<String, FacultyPreference> entry : facultyPreferences.entrySet()) {
                FacultyPreference pref = entry.getValue();
                // Example usage: Add a conflict if the preference is not met
                if (!pref.isPreferenceMet()) {
                    conflicts.add("Conflict for faculty: " + entry.getKey());
                }

                // Check if timetable slots match faculty preferences
                // Add any conflicts to the conflicts list
            }

            if (!conflicts.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "status", "error",
                        "message", "Timetable generation failed due to faculty preference conflicts",
                        "conflicts", conflicts
                ));
            }

            // Generate timetable considering preferences
            timetableService.generateSchedule(request);
            return ResponseEntity.ok("Schedule generated successfully!");
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "error",
                    "message", "Failed to generate timetable: " + e.getMessage()
            ));
        }
    }

    @PostMapping("/validate")
    public ResponseEntity<?> validateTimetable() {
        try {
            Map<String, Object> validationResult = timetableService.validateSchedule();
            
            if (validationResult == null) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "status", "error",
                    "message", "Validation failed: No results returned"
                ));
            }

            Boolean isValid = (Boolean) validationResult.get("isValid");
            Object violationsObj = validationResult.get("violations");
            List<String> violations = (violationsObj instanceof List<?> list && list.stream().allMatch(item -> item instanceof String))
                    ? ((List<?>) violationsObj).stream()
                        .filter(item -> item instanceof String)
                        .map(item -> (String) item)
                        .toList()
                    : Collections.emptyList();

            if (Boolean.TRUE.equals(isValid)) {
                return ResponseEntity.ok(Map.of(
                    "status", "valid",
                    "message", "Timetable is valid and meets all requirements"
                ));
            } else {
                return ResponseEntity.ok(Map.of(
                    "status", "invalid",
                    "message", "Timetable validation failed",
                    "violations", violations != null ? violations : Collections.emptyList()
                ));
            }
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "status", "error",
                "message", "Failed to validate timetable: " + e.getMessage()
            ));
        }
    }

    @GetMapping
    public List<TimetableEntry> getAllTimetableEntries() {
        return timetableService.getAllEntries();
    }

    @GetMapping("/download/csv")
    public void downloadCSV(HttpServletResponse response) throws IOException {
        response.setContentType("text/csv");
        response.setHeader("Content-Disposition", "attachment; filename=timetable.csv");

        Map<String, List<String>> daySlotMatrix = timetableService.buildDaySlotMatrix();
        String[] timeSlots = timetableService.getTimeSlots();

        PrintWriter writer = response.getWriter();
        writer.print("Day - Time");
        for (String timeSlot : timeSlots) {
            writer.print("," + timeSlot);
        }
        writer.println();

        for (String day : timetableService.getDays()) {
            writer.print(day);
            List<String> dayEntries = daySlotMatrix.get(day);
            for (String entry : dayEntries) {
                writer.print("," + entry);
            }
            writer.println();
        }
        writer.flush();
        writer.close();
    }

    @GetMapping("/download/excel")
    public void downloadExcel(HttpServletResponse response) throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=timetable.xlsx");

        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Timetable");

        Map<String, List<String>> daySlotMatrix = timetableService.buildDaySlotMatrix();
        String[] timeSlots = timetableService.getTimeSlots();
        String[] days = timetableService.getDays().toArray(new String[0]);

        Row headerRow = sheet.createRow(0);
        Cell headerCell = headerRow.createCell(0);
        headerCell.setCellValue("Day - Time");
        for (int i = 0; i < timeSlots.length; i++) {
            Cell cell = headerRow.createCell(i + 1);
            cell.setCellValue(timeSlots[i]);
        }

        for (int i = 0; i < days.length; i++) {
            Row row = sheet.createRow(i + 1);
            Cell dayCell = row.createCell(0);
            dayCell.setCellValue(days[i]);
            List<String> dayEntries = daySlotMatrix.get(days[i]);
            for (int j = 0; j < dayEntries.size(); j++) {
                Cell cell = row.createCell(j + 1);
                cell.setCellValue(dayEntries.get(j));
            }
        }
        for (int i = 0; i <= timeSlots.length; i++) {
            sheet.autoSizeColumn(i);
        }
        workbook.write(response.getOutputStream());
        workbook.close();
    }

    @PostMapping("/updateTeacher")
    public ResponseEntity<String> updateTeacherAvailability(
            @RequestParam String teacher,
            @RequestParam boolean available,
            @RequestParam(required = false) String newTeacher,
            @RequestParam boolean updateOldTimetable) {
        timetableService.updateTeacherAvailability(teacher, available, newTeacher, updateOldTimetable);
        return ResponseEntity.ok("Teacher update processed for " + teacher);
    }
}
