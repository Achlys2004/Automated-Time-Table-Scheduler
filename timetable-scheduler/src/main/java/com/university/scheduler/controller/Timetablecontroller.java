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
                System.out.println("Processing subject: " + subject.getName() + ", Code: " + subject.getCode() +
                        ", Hours: " + subject.getHoursPerWeek());
                Subject existingSubject = subjectRepository.findByCode(subject.getCode());
                if (existingSubject == null) {
                    // Set default values if missing
                    if (subject.getHoursPerWeek() == 0) {
                        subject.setHoursPerWeek(3);
                        System.out.println("  Setting default hours: 3");
                    }
                    if (subject.getDepartment() == null) {
                        subject.setDepartment(request.getDepartment());
                        System.out.println("  Setting department: " + request.getDepartment());
                    }
                    subjectRepository.save(subject);
                    System.out.println("  Saved new subject: " + subject.getName());
                } else {
                    // Update existing subject with new values
                    existingSubject.setName(subject.getName());
                    existingSubject.setFaculty(subject.getFaculty());
                    existingSubject.setHoursPerWeek(subject.getHoursPerWeek() > 0 ? subject.getHoursPerWeek()
                            : existingSubject.getHoursPerWeek());
                    existingSubject.setLabRequired(subject.isLabRequired());
                    existingSubject.setDepartment(
                            subject.getDepartment() != null ? subject.getDepartment() : request.getDepartment());
                    subjectRepository.save(existingSubject);
                    System.out.println("  Updated existing subject: " + existingSubject.getName());
                }
            }
        }

        // Critical debugging - Check if subjects are available in the repository
        List<Subject> allSubjects = subjectRepository.findAll();
        System.out.println("\nAll subjects in repository (" + allSubjects.size() + "):");
        for (Subject s : allSubjects) {
            System.out.println("  " + s.getCode() + ": " + s.getName() + ", Faculty: " + s.getFaculty() +
                    ", Hours: " + s.getHoursPerWeek() + ", Department: " + s.getDepartment());
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

        // Write header row with time slots
        writer.print("Day - Time");
        for (String timeSlot : timeSlots) {
            writer.print("," + timeSlot);
        }
        writer.println();

        // Write each day's schedule
        for (String day : timetableService.getDays()) {
            writer.print(day);
            List<String> dayEntries = daySlotMatrix.get(day);
            for (String entry : dayEntries) {
                writer.print("," + entry);
            }
            writer.println();
        }
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

        // Create header row
        Row headerRow = sheet.createRow(0);
        Cell headerCell = headerRow.createCell(0);
        headerCell.setCellValue("Day - Time");

        for (int i = 0; i < timeSlots.length; i++) {
            Cell cell = headerRow.createCell(i + 1);
            cell.setCellValue(timeSlots[i]);
        }

        // Create data rows for each day
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

        // Auto-size columns
        for (int i = 0; i <= timeSlots.length; i++) {
            sheet.autoSizeColumn(i);
        }

        workbook.write(response.getOutputStream());
        workbook.close();
    }
}
