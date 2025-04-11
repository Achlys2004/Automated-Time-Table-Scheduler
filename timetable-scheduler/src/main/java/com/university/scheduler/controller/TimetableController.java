package com.university.scheduler.controller;

import com.university.scheduler.model.TimetableEntry;
import com.university.scheduler.model.TimetableRequest;
import com.university.scheduler.service.TimetableService;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/timetable")
public class TimetableController {

    private final TimetableService timetableService;

    public TimetableController(TimetableService timetableService) {
        this.timetableService = timetableService;
    }

    @PostMapping("/generate")
    public ResponseEntity<String> generateSchedule(@RequestBody TimetableRequest request) {
        // Validation
        if (request.getSubjects() == null || request.getSubjects().isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("Invalid request: Subjects list is required");
        }
        timetableService.generateSchedule(request);
        return ResponseEntity.ok("Schedule generated successfully!");
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
        String[] days = timetableService.getDays();

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
