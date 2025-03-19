package com.university.scheduler.controller;

import com.university.scheduler.model.Subject;
import com.university.scheduler.model.TimetableEntry;
import com.university.scheduler.model.TimetableRequest;
import com.university.scheduler.repository.SubjectRepository;
import com.university.scheduler.service.TimetableService;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.Map;

/**
 * REST Controller for timetable endpoints:
 * - POST /api/timetable/generate (generates timetable based on JSON input)
 * - GET /api/timetable (returns raw JSON array of TimetableEntry)
 * - GET /api/timetable/download/csv
 * - GET /api/timetable/download/excel
 */
@RestController
@RequestMapping("/api/timetable")
public class TimetableController {

    private final TimetableService timetableService;
    private final SubjectRepository subjectRepository;

    public TimetableController(TimetableService timetableService, SubjectRepository subjectRepository) {
        this.timetableService = timetableService;
        this.subjectRepository = subjectRepository;
    }

    /**
     * Generates a schedule based on the JSON request and saves it in the DB.
     */
    @PostMapping("/generate")
    public ResponseEntity<String> generateSchedule(@RequestBody TimetableRequest request) {
        // Save subjects to the database if they don't exist
        if (request.getSubjects() != null) {
            for (Subject subject : request.getSubjects()) {
                Subject existingSubject = subjectRepository.findByCode(subject.getCode());
                if (existingSubject == null) {
                    // Set default values if missing
                    if (subject.getHoursPerWeek() == 0) {
                        subject.setHoursPerWeek(3);
                    }
                    if (subject.getDepartment() == null) {
                        subject.setDepartment(request.getDepartment());
                    }
                    subjectRepository.save(subject);
                }
            }
        }

        timetableService.generateSchedule(request);
        return ResponseEntity.ok("Schedule generated successfully!");
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

        String[] timeSlots = timetableService.getTimeSlots();
        String[] days = timetableService.getDays();
        Map<String, List<String>> dayToSlots = timetableService.buildDaySlotMatrix();

        try (PrintWriter writer = response.getWriter()) {
            writer.print("Day - Time");
            for (String slot : timeSlots) {
                writer.print("," + slot);
            }
            writer.println();

            for (String day : days) {
                writer.print(day);
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

            Row headerRow = sheet.createRow(0);
            headerRow.createCell(0).setCellValue("Day - Time");
            for (int i = 0; i < timeSlots.length; i++) {
                headerRow.createCell(i + 1).setCellValue(timeSlots[i]);
            }

            for (int rowIndex = 0; rowIndex < days.length; rowIndex++) {
                String day = days[rowIndex];
                Row row = sheet.createRow(rowIndex + 1);
                row.createCell(0).setCellValue(day);

                List<String> slots = dayToSlots.get(day);
                for (int colIndex = 0; colIndex < slots.size(); colIndex++) {
                    row.createCell(colIndex + 1).setCellValue(slots.get(colIndex));
                }
            }

            for (int i = 0; i <= timeSlots.length; i++) {
                sheet.autoSizeColumn(i);
            }

            workbook.write(response.getOutputStream());
        }
    }
}
