package com.university.scheduler.service;

import com.university.scheduler.model.TimetableEntry;
import com.university.scheduler.repository.TimetableRepository;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Service layer for timetable logic:
 * - Generate a default schedule
 * - Retrieve all entries
 * - Build a day-slot matrix for CSV/Excel
 */
@Service
public class TimetableService {

    private final TimetableRepository repository;

    // Days of the week
    private static final String[] DAYS = {
            "Monday", "Tuesday", "Wednesday", "Thursday", "Friday"
    };

    // 11 time slots matching your desired schedule
    private static final String[] TIME_SLOTS = {
            "8:45am - 9.45am",
            "9.45am -10.15am",
            "10.15am - 11.00am",
            "11am - 11.30am",
            "11.30am - 12.15pm",
            "12.15pm -1.00pm",
            "1.00pm -1.45pm",
            "1.45pm - 2.30pm",
            "2.30pm - 3.15pm",
            "3.15pm - 4.00pm",
            "4.00pm - 4.45pm"
    };

    public TimetableService(TimetableRepository repository) {
        this.repository = repository;
    }

    /**
     * Example method to generate a default schedule with "Subject" for most slots
     * and "Break" at certain times. Adjust as needed.
     */
    public void generateDefaultSchedule() {
        // Clear existing data
        repository.deleteAll();

        // Fill each day/time slot with "Subject" except for a couple of "Break" slots
        // For example, let's make sessionNumber 4 and 8 as Breaks.
        for (String day : DAYS) {
            for (int i = 0; i < TIME_SLOTS.length; i++) {
                int sessionNum = i + 1; // 1-based
                String subject = (sessionNum == 4 || sessionNum == 8) ? "Break" : "Subject";
                TimetableEntry entry = new TimetableEntry(day, sessionNum, subject);
                repository.save(entry);
            }
        }
    }

    public List<TimetableEntry> getAllEntries() {
        return repository.findAll();
    }

    /**
     * Builds a map: Day -> List of 11 subjects/breaks (one for each timeslot).
     * This is used to generate the row/column layout for CSV/Excel.
     */
    public Map<String, List<String>> buildDaySlotMatrix() {
        // Initialize the data structure with "Break" or empty
        Map<String, List<String>> dayToSlots = new LinkedHashMap<>();
        for (String day : DAYS) {
            List<String> slots = new ArrayList<>(Collections.nCopies(TIME_SLOTS.length, "Break"));
            dayToSlots.put(day, slots);
        }

        // Fill from the DB
        List<TimetableEntry> entries = repository.findAll();
        for (TimetableEntry entry : entries) {
            String day = entry.getDay();
            int index = entry.getSessionNumber() - 1; // zero-based
            if (index >= 0 && index < TIME_SLOTS.length) {
                dayToSlots.get(day).set(index, entry.getSubject());
            }
        }
        return dayToSlots;
    }

    public String[] getDays() {
        return DAYS;
    }

    public String[] getTimeSlots() {
        return TIME_SLOTS;
    }
}
