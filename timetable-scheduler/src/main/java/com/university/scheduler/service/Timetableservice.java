package com.university.scheduler.service;

import com.university.scheduler.model.FacultyPreference;
import com.university.scheduler.model.Subject;
import com.university.scheduler.model.TimetableEntry;
import com.university.scheduler.model.TimetableRequest;
import com.university.scheduler.repository.TimetableRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional
public class TimetableService {

    private final TimetableRepository timetableRepository;

    public TimetableService(TimetableRepository timetableRepository) {
        this.timetableRepository = timetableRepository;
    }

    /**
     * Generates a schedule based on the JSON request and saves it in the DB.
     * Uses backtracking to assign subjects to available time slots while ensuring:
     * - Each subject is scheduled at most maxSessionsPerDay times per day.
     * - If a faculty has a preferred day, that is honored (time preference is
     * ignored).
     * - Break time slots are set as "BREAK".
     */
    public void generateSchedule(TimetableRequest request) {
        List<TimetableEntry> entries = new ArrayList<>();
        timetableRepository.deleteAll(); // Clear existing entries

        // Determine maximum sessions per day per subject (default 2 if not provided)
        int maxSessionsPerDay = (request.getMaxSessionsPerDay() != null) ? request.getMaxSessionsPerDay() : 2;

        // Use provided availableTimeSlots, or fallback to default values.
        List<String> timeSlots = (request.getAvailableTimeSlots() != null && !request.getAvailableTimeSlots().isEmpty())
                ? request.getAvailableTimeSlots()
                : Arrays.asList(getTimeSlots());
        // Use provided breakTimes, or fallback to default break times.
        Set<String> breakSlots = (request.getBreakTimes() != null && !request.getBreakTimes().isEmpty())
                ? new HashSet<>(request.getBreakTimes())
                : new HashSet<>(Arrays.asList("10:15am - 11:00am", "1:00pm - 1:45pm"));

        List<Subject> subjects = request.getSubjects();

        // Track allocation count per subject code (initially from subjects list)
        Map<String, Integer> subjectAllocationCount = new HashMap<>();
        for (Subject subject : subjects) {
            if (subject.getHoursPerWeek() == 0) {
                subject.setHoursPerWeek(3); // default if missing
            }
            subjectAllocationCount.put(subject.getCode(), 0);
        }

        // Process faculty preferences into a map (by faculty)
        Map<String, FacultyPreference> facultyPreferenceMap = new HashMap<>();
        if (request.getFacultyPreferences() != null) {
            for (FacultyPreference pref : request.getFacultyPreferences()) {
                facultyPreferenceMap.put(pref.getFaculty(), pref);
            }
        }

        List<String> days = List.of("Monday", "Tuesday", "Wednesday", "Thursday", "Friday");

        // For each day, schedule the time slots.
        for (String day : days) {
            // Create an array to hold subject assignments for each slot.
            Subject[] assignment = new Subject[timeSlots.size()];
            boolean[] isBreakSlot = new boolean[timeSlots.size()];
            // Mark break slots
            for (int i = 0; i < timeSlots.size(); i++) {
                String slot = timeSlots.get(i).trim();
                if (breakSlots.stream().anyMatch(b -> b.trim().equalsIgnoreCase(slot))) {
                    isBreakSlot[i] = true;
                    assignment[i] = null; // will be set as "BREAK"
                } else {
                    isBreakSlot[i] = false;
                }
            }
            // Use backtracking to assign subjects for non-break slots.
            // Create a copy of the initial allocation counts for this day.
            Map<String, Integer> dailyAllocation = new HashMap<>(subjectAllocationCount);
            backtrackAssign(0, assignment, timeSlots.size(), isBreakSlot,
                    subjects, dailyAllocation, maxSessionsPerDay, day, facultyPreferenceMap);

            // Build timetable entries for this day.
            for (int i = 0; i < timeSlots.size(); i++) {
                TimetableEntry entry = new TimetableEntry();
                entry.setDay(day);
                entry.setSessionNumber(i + 1); // 1-indexed
                if (isBreakSlot[i]) {
                    entry.setSubject("BREAK");
                } else if (assignment[i] != null) {
                    entry.setSubject(assignment[i].getName() + " (" + assignment[i].getFaculty() + ")");
                } else {
                    entry.setSubject("Free Period");
                }
                entries.add(entry);
            }
        }

        timetableRepository.saveAll(entries);
    }

    /**
     * Backtracking algorithm to assign subjects to non-break time slots.
     *
     * @param index              current time slot index.
     * @param assignment         current subject assignment array.
     * @param totalSlots         total number of time slots.
     * @param isBreakSlot        boolean array indicating break slots.
     * @param subjects           list of subjects.
     * @param dailyAllocation    allocation count for each subject for the day.
     * @param maxSessionsPerDay  maximum sessions per subject per day.
     * @param day                current day.
     * @param facultyPreferences map of faculty preferences by faculty.
     * @return true if a valid assignment is found; false otherwise.
     */
    private boolean backtrackAssign(int index, Subject[] assignment, int totalSlots, boolean[] isBreakSlot,
            List<Subject> subjects, Map<String, Integer> dailyAllocation,
            int maxSessionsPerDay, String day, Map<String, FacultyPreference> facultyPreferences) {
        if (index >= totalSlots) {
            return true;
        }
        if (isBreakSlot[index]) {
            return backtrackAssign(index + 1, assignment, totalSlots, isBreakSlot, subjects, dailyAllocation,
                    maxSessionsPerDay, day, facultyPreferences);
        }

        // Get previous non-break assignment to avoid consecutive same professor, if
        // possible.
        int prev = index - 1;
        while (prev >= 0 && isBreakSlot[prev]) {
            prev--;
        }
        final String prevFaculty = (prev >= 0 && assignment[prev] != null) ? assignment[prev].getFaculty() : null;

        // Filter subjects: ignore time preference now, only consider preferred day if
        // available.
        List<Subject> candidateSubjects = subjects.stream()
                .filter(subject -> {
                    FacultyPreference pref = facultyPreferences.get(subject.getFaculty());
                    // If no preference or no preferred days specified, accept subject.
                    if (pref == null || pref.getPreferredDays() == null)
                        return true;
                    return pref.getPreferredDays().contains(day);
                })
                .filter(subject -> dailyAllocation.get(subject.getCode()) < subject.getHoursPerWeek() &&
                        dailyAllocation.get(subject.getCode()) < maxSessionsPerDay)
                .collect(Collectors.toList());

        // First, try to filter out subjects with the same consecutive professor.
        List<Subject> filteredCandidates = candidateSubjects.stream()
                .filter(subject -> prevFaculty == null || !prevFaculty.equals(subject.getFaculty()))
                .collect(Collectors.toList());
        // If filtering leaves no candidate, fallback to all candidates.
        if (filteredCandidates.isEmpty()) {
            filteredCandidates = candidateSubjects;
        }
        // Sort candidates by current allocation count (ascending).
        filteredCandidates.sort(Comparator.comparing(subject -> dailyAllocation.get(subject.getCode())));
        for (Subject subject : filteredCandidates) {
            assignment[index] = subject;
            dailyAllocation.put(subject.getCode(), dailyAllocation.get(subject.getCode()) + 1);
            if (backtrackAssign(index + 1, assignment, totalSlots, isBreakSlot, subjects, dailyAllocation,
                    maxSessionsPerDay, day, facultyPreferences)) {
                return true;
            }
            dailyAllocation.put(subject.getCode(), dailyAllocation.get(subject.getCode()) - 1);
            assignment[index] = null;
        }
        return false;
    }

    /**
     * Returns all timetable entries.
     */
    public List<TimetableEntry> getAllEntries() {
        return timetableRepository.findAll();
    }

    /**
     * Returns default time slots.
     */
    public String[] getTimeSlots() {
        return new String[] {
                "8:45am - 9:45am",
                "9:45am - 10:15am",
                "10:15am - 11:00am",
                "11:30am - 12:15pm",
                "12:15pm - 1:00pm",
                "1:00pm - 1:45pm",
                "2:30pm - 3:15pm",
                "3:15pm - 4:00pm",
                "4:00pm - 4:45pm"
        };
    }

    /**
     * Returns days of the week.
     */
    public String[] getDays() {
        return new String[] { "Monday", "Tuesday", "Wednesday", "Thursday", "Friday" };
    }

    /**
     * Builds a day-slot matrix for CSV/Excel export.
     */
    public Map<String, List<String>> buildDaySlotMatrix() {
        Map<String, List<String>> matrix = new HashMap<>();
        List<TimetableEntry> entries = getAllEntries();

        // Group entries by day and session number.
        Map<String, Map<Integer, String>> daySessionMap = new HashMap<>();
        for (TimetableEntry entry : entries) {
            daySessionMap.computeIfAbsent(entry.getDay(), k -> new HashMap<>())
                    .put(entry.getSessionNumber(), entry.getSubject());
        }

        for (String day : getDays()) {
            List<String> subjects = new ArrayList<>();
            Map<Integer, String> sessionMap = daySessionMap.getOrDefault(day, Collections.emptyMap());
            for (int i = 1; i <= getTimeSlots().length; i++) {
                subjects.add(sessionMap.getOrDefault(i, ""));
            }
            matrix.put(day, subjects);
        }

        return matrix;
    }
}
