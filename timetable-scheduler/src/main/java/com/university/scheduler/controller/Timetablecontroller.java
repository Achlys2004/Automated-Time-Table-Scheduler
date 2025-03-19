package com.university.scheduler.controller;

import com.university.scheduler.model.TimetableEntry;
import com.university.scheduler.service.TimetableService;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.Map;

/**
 * REST Controller for timetable endpoints:
 * - POST /api/timetable/generate (generates default schedule)
 * - GET /api/timetable (returns raw JSON array of TimetableEntry)
 * - GET /api/timetable/download/csv
 * - GET /api/timetable/download/excel
 */
@RestController
@RequestMapping("/api/timetable")
public class TimetableController {

    private final TimetableService timetableService;

    public TimetableController(TimetableService timetableService) {
        this.timetableService = timetableService;
    }

    /**
     * Generates a default schedule and saves it in the DB.
     */
    @PostMapping("/generate")
    public String generateSchedule() {
        timetableService.generateDefaultSchedule();
        return "Default schedule generated successfully!";
    }

    /**
     * Returns raw JSON of all timetable entries.
     */
    @GetMapping
    public List<TimetableEntry> getAllTimetableEntries() {
        return timetableService.getAllEntries();
    }

    /**
     * Downloads the timetable in CSV format with the row/column layout.
     */
    @GetMapping("/download/csv")
    public void downloadCSV(HttpServletResponse response) throws IOException {
        response.setContentType("text/csv");
        response.setHeader("Content-Disposition", "attachment; filename=\"timetable.csv\"");

        // Prepare data
        String[] timeSlots = timetableService.getTimeSlots();
        String[] days = timetableService.getDays();
        Map<String, List<String>> dayToSlots = timetableService.buildDaySlotMatrix();

        try (PrintWriter writer = response.getWriter()) {
            // Write header row
            writer.print("Day -Time");
            for (String slot : timeSlots) {
                writer.print("," + slot);
            }
            writer.println();

            // For each day, write one row
            for (String day : days) {
                writer.print(day); // First column is the day
                List<String> slots = dayToSlots.get(day);
                for (String subject : slots) {
                    writer.print("," + subject);
                }
                writer.println();
            }
        }
    }

    /**
     * Downloads the timetable in Excel format with the row/column layout.
     */
    @GetMapping("/download/excel")
    public void downloadExcel(HttpServletResponse response) throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=\"timetable.xlsx\"");

        String[] timeSlots = timetableService.getTimeSlots();
        String[] days = timetableService.getDays();
        Map<String, List<String>> dayToSlots = timetableService.buildDaySlotMatrix();

        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Timetable");

            // Create header row
            Row headerRow = sheet.createRow(0);
            headerRow.createCell(0).setCellValue("Day -Time");
            for (int i = 0; i < timeSlots.length; i++) {
                headerRow.createCell(i + 1).setCellValue(timeSlots[i]);
            }

            // Create rows for each day
            for (int rowIndex = 0; rowIndex < days.length; rowIndex++) {
                String day = days[rowIndex];
                Row row = sheet.createRow(rowIndex + 1);
                row.createCell(0).setCellValue(day);

                List<String> slots = dayToSlots.get(day);
                for (int colIndex = 0; colIndex < slots.size(); colIndex++) {
                    row.createCell(colIndex + 1).setCellValue(slots.get(colIndex));
                }
            }

            // Auto-size columns for readability
            for (int i = 0; i <= timeSlots.length; i++) {
                sheet.autoSizeColumn(i);
            }

            workbook.write(response.getOutputStream());
        }
    }
}
