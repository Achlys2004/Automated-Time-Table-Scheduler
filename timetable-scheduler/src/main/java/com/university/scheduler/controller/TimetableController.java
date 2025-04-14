package com.university.scheduler.controller;

import com.university.scheduler.model.TimetableEntry;
import com.university.scheduler.model.TimetableRequest;
import com.university.scheduler.service.TimetableService;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.*;

@RestController
@RequestMapping("/api/timetable")
public class TimetableController {

    @Autowired
    private TimetableService timetableService;

    // Removed unused field to resolve the compile error

    public TimetableController(TimetableService timetableService) {
        this.timetableService = timetableService;
    }

    @PostMapping("/generate")
    public ResponseEntity<?> generateTimetable(@RequestBody TimetableRequest request) {
        try {
            // Validate request
            if (request.getSubjects() == null || request.getSubjects().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                    "status", "error",
                    "message", "Invalid request: Subjects list is required"
                ));
            }

            // Generate timetable
            timetableService.generateSchedule(request);

            // Get validation results
            Map<String, Object> validationResult = timetableService.validateSchedule();
            Boolean isValid = (Boolean) validationResult.get("isValid");
            List<?> violationsRaw = (List<?>) validationResult.get("violations");
            List<String> violations = new ArrayList<>();
            for (Object violation : violationsRaw) {
                if (violation instanceof String) {
                    violations.add((String) violation);
                }
            }

            if (Boolean.TRUE.equals(isValid)) {
                return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "message", "Timetable generated successfully"
                ));
            } else {
                return ResponseEntity.ok(Map.of(
                    "status", "warning",
                    "message", "Timetable generated with warnings",
                    "violations", violations
                ));
            }
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                "status", "error",
                "message", "Failed to generate timetable: " + e.getMessage()
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
