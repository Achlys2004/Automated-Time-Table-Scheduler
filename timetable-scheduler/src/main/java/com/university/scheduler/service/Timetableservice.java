package com.university.scheduler.service;

import com.university.scheduler.model.Subject;
import com.university.scheduler.model.TimetableEntry;
import com.university.scheduler.model.TimetableRequest;
import com.university.scheduler.repository.SubjectRepository;
import com.university.scheduler.repository.TimetableRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.util.*;
// import java.util.stream.Collectors;

@Service
@Transactional
public class TimetableService {

    private final TimetableRepository timetableRepository;
    private final SubjectRepository subjectRepository;

    public TimetableService(TimetableRepository timetableRepository,
            SubjectRepository subjectRepository) {
        this.timetableRepository = timetableRepository;
        this.subjectRepository = subjectRepository;
    }

    /**
     * Generates a "perfect" timetable using backtracking.
     * Each subject with a lab gets 3 additional sessions labeled "SubjectName Lab".
     * We respect maxSessionsPerDay for both theory and lab sessions.
     */
    public void generateSchedule(TimetableRequest request) {
        // 1) Clear old entries
        timetableRepository.deleteAll();
        System.out.println("Cleared existing timetable entries");

        // 2) Fetch subjects
        List<Subject> subjects = fetchSubjects(request);
        if (subjects.isEmpty()) {
            System.out.println("No subjects found for scheduling!");
            return;
        }

        // Debug output to verify subjects are loaded
        System.out.println("Subjects to be scheduled (" + subjects.size() + "):");
        for (Subject subject : subjects) {
            System.out.println("  " + subject.getCode() + ": " + subject.getName() +
                    ", Faculty: " + subject.getFaculty() +
                    ", Hours: " + subject.getHoursPerWeek());
        }

        // 3) Build list of time slots (excluding breaks)
        List<String> timeSlots = (request.getAvailableTimeSlots() == null ||
                request.getAvailableTimeSlots().isEmpty())
                        ? Arrays.asList(getTimeSlots())
                        : request.getAvailableTimeSlots();

        // If request doesn't supply breakTimes, default them
        Set<String> breakTimes = (request.getBreakTimes() == null)
                ? new HashSet<>(Arrays.asList("11:00am - 11:30am", "1:45pm - 2:30pm"))
                : new HashSet<>(request.getBreakTimes());

        List<Slot> allSlots = buildAllSlots(getDays(), timeSlots, breakTimes);

        // 4) Prepare subject allocations
        // Each subject has theoryLeft = hoursPerWeek,
        // and labLeft = 3 if labRequired
        int maxPerDay = (request.getMaxSessionsPerDay() == null) ? 2 : request.getMaxSessionsPerDay();
        List<SubjectAllocation> subjectAllocs = new ArrayList<>();
        for (Subject s : subjects) {
            int theory = s.getHoursPerWeek();
            int lab = s.isLabRequired() ? 3 : 0;
            subjectAllocs.add(new SubjectAllocation(s.getCode(), s.getName(), s.getFaculty(),
                    theory, lab, maxPerDay));
        }

        // 5) Backtracking
        List<TimetableEntry> solution = new ArrayList<>();
        boolean success = backtrack(0, allSlots, subjectAllocs, new ArrayList<>(), solution);

        if (!success) {
            System.out.println("No valid timetable could be generated with the given constraints!");
            // Still create some valid timetable, even if it doesn't satisfy all constraints
            for (Slot slot : allSlots) {
                TimetableEntry entry = new TimetableEntry();
                entry.setDay(slot.day);
                entry.setSessionNumber(slot.timeIndex + 1);
                entry.setSubject("Free Period");
                solution.add(entry);
            }
        }

        // 6) Save solution
        timetableRepository.saveAll(solution);
        System.out.println("Saved " + solution.size() + " timetable entries to database");

        // 7) Print summary
        System.out.println("\n--- Final Timetable ---");
        for (TimetableEntry e : solution) {
            System.out.println(e.getDay() + ", Session " + e.getSessionNumber() + ": " + e.getSubject());
        }
    }

    /**
     * Recursive backtracking function.
     * 
     * @param slotIndex     index in the allSlots list
     * @param allSlots      list of all non-break slots
     * @param subjectAllocs stateful list tracking how many theory/lab sessions
     *                      remain
     * @param partial       current partial solution (TimetableEntry for each slot
     *                      so far)
     * @param finalSolution out-parameter for the complete schedule if found
     * @return true if a complete, valid assignment was found
     */
    private boolean backtrack(int slotIndex,
            List<Slot> allSlots,
            List<SubjectAllocation> subjectAllocs,
            List<TimetableEntry> partial,
            List<TimetableEntry> finalSolution) {

        // Base case: if we've assigned all slots, check if all required sessions are
        // used
        if (slotIndex == allSlots.size()) {
            // Verify all subjects have 0 theoryLeft and 0 labLeft
            boolean allAllocated = true;
            for (SubjectAllocation sa : subjectAllocs) {
                if (sa.theoryLeft > 0 || sa.labLeft > 0) {
                    // Not all required sessions allocated => no success
                    allAllocated = false;
                    break;
                }
            }

            if (allAllocated) {
                // All required sessions allocated => success
                finalSolution.addAll(partial);
                return true;
            }
            return false;
        }

        Slot currentSlot = allSlots.get(slotIndex);
        System.out.println("Processing slot: " + currentSlot.day + ", time index " + currentSlot.timeIndex);

        // Try to place each subject (theory or lab) if constraints allow
        for (SubjectAllocation sa : subjectAllocs) {
            // 1) If we have theory sessions left, try theory
            if (sa.theoryLeft > 0) {
                if (sa.canScheduleTheory(currentSlot.day)) {
                    System.out.println("  Trying theory for " + sa.subjectName);

                    // Place a theory session
                    sa.theoryLeft--;
                    sa.incrementDayCount(currentSlot.day);

                    TimetableEntry entry = new TimetableEntry();
                    entry.setDay(currentSlot.day);
                    entry.setSessionNumber(currentSlot.timeIndex + 1);
                    entry.setSubject(sa.faculty + " - " + sa.subjectName); // e.g. "Dr. Smith - Data Structures"

                    partial.add(entry);

                    // Recurse
                    if (backtrack(slotIndex + 1, allSlots, subjectAllocs, partial, finalSolution)) {
                        return true;
                    }

                    // Backtrack (undo)
                    partial.remove(partial.size() - 1);
                    sa.decrementDayCount(currentSlot.day);
                    sa.theoryLeft++;
                    System.out.println("  Backtracking from theory for " + sa.subjectName);
                }
            }

            // 2) If we have lab sessions left, try lab
            if (sa.labLeft > 0) {
                if (sa.canScheduleTheory(currentSlot.day)) {
                    System.out.println("  Trying lab for " + sa.subjectName);

                    // We treat labs like theory in terms of daily limit
                    sa.labLeft--;
                    sa.incrementDayCount(currentSlot.day);

                    TimetableEntry entry = new TimetableEntry();
                    entry.setDay(currentSlot.day);
                    entry.setSessionNumber(currentSlot.timeIndex + 1);
                    // Mark it as "SubjectName Lab"
                    entry.setSubject(sa.faculty + " - " + sa.subjectName + " Lab");

                    partial.add(entry);

                    // Recurse
                    if (backtrack(slotIndex + 1, allSlots, subjectAllocs, partial, finalSolution)) {
                        return true;
                    }

                    // Backtrack (undo)
                    partial.remove(partial.size() - 1);
                    sa.decrementDayCount(currentSlot.day);
                    sa.labLeft++;
                    System.out.println("  Backtracking from lab for " + sa.subjectName);
                }
            }
        }

        // 3) If we couldn't schedule any subject in this slot, we can place "Free
        // Period"
        System.out.println("  No subject could be scheduled, using Free Period");
        TimetableEntry freeEntry = new TimetableEntry();
        freeEntry.setDay(currentSlot.day);
        freeEntry.setSessionNumber(currentSlot.timeIndex + 1);
        freeEntry.setSubject("Free Period");
        partial.add(freeEntry);

        if (backtrack(slotIndex + 1, allSlots, subjectAllocs, partial, finalSolution)) {
            return true;
        }

        // Backtrack free period
        partial.remove(partial.size() - 1);
        System.out.println("  Backtracking from Free Period");

        // No assignment led to a valid solution
        return false;
    }

    // ----------------------
    // Helper methods
    // ----------------------

    /**
     * Build the list of non-break slots for all days/timeSlots.
     */
    private List<Slot> buildAllSlots(String[] days, List<String> timeSlots, Set<String> breakTimes) {
        List<Slot> allSlots = new ArrayList<>();
        for (String day : days) {
            for (int i = 0; i < timeSlots.size(); i++) {
                String ts = timeSlots.get(i);
                if (!breakTimes.contains(ts)) {
                    Slot slot = new Slot(day, i, ts);
                    allSlots.add(slot);
                }
            }
        }
        return allSlots;
    }

    private List<Subject> fetchSubjects(TimetableRequest request) {
        if (request.getSubjects() != null && !request.getSubjects().isEmpty()) {
            return request.getSubjects();
        } else if (request.getDepartment() != null && !request.getDepartment().isEmpty()) {
            return subjectRepository.findByDepartment(request.getDepartment());
        }
        return subjectRepository.findAll();
    }

    public String[] getDays() {
        return new String[] { "Monday", "Tuesday", "Wednesday", "Thursday", "Friday" };
    }

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

    // For CSV/Excel export if needed
    public Map<String, List<String>> buildDaySlotMatrix() {
        List<TimetableEntry> entries = timetableRepository.findAll();
        Map<String, Map<Integer, String>> daySessionMap = new HashMap<>();

        for (TimetableEntry e : entries) {
            daySessionMap
                    .computeIfAbsent(e.getDay(), d -> new HashMap<>())
                    .put(e.getSessionNumber(), e.getSubject());
        }

        Map<String, List<String>> matrix = new LinkedHashMap<>();
        for (String day : getDays()) {
            List<String> row = new ArrayList<>();
            Map<Integer, String> sessionMap = daySessionMap.getOrDefault(day, Collections.emptyMap());
            for (int i = 1; i <= getTimeSlots().length; i++) {
                row.add(sessionMap.getOrDefault(i, "Free Period"));
            }
            matrix.put(day, row);
        }
        return matrix;
    }

    /**
     * Returns all timetable entries from the repository.
     * This method is used by the controller to fetch all entries for display.
     */
    public List<TimetableEntry> getAllEntries() {
        return timetableRepository.findAll();
    }

    // ----------------------
    // Inner Classes
    // ----------------------

    /**
     * Represents a single timeslot on a specific day.
     */
    private static class Slot {
        String day;
        int timeIndex;
        String timeText; // Used for debugging or UI display

        Slot(String day, int timeIndex, String timeText) {
            this.day = day;
            this.timeIndex = timeIndex;
            this.timeText = timeText;
        }

        @Override
        public String toString() {
            return day + " at " + timeText + " (index " + timeIndex + ")";
        }
    }

    /**
     * Tracks how many theory/lab sessions remain for a subject, and how many
     * sessions have been allocated per day (to respect maxSessionsPerDay).
     */
    private static class SubjectAllocation {
        String code; // Subject identifier, useful for logging
        String subjectName;
        String faculty;
        int theoryLeft;
        int labLeft;
        int maxPerDay;
        Map<String, Integer> dailyCount;

        SubjectAllocation(String code, String subjectName, String faculty,
                int theoryLeft, int labLeft, int maxPerDay) {
            this.code = code;
            this.subjectName = subjectName;
            this.faculty = faculty;
            this.theoryLeft = theoryLeft;
            this.labLeft = labLeft;
            this.maxPerDay = maxPerDay;
            this.dailyCount = new HashMap<>();
            dailyCount.put("Monday", 0);
            dailyCount.put("Tuesday", 0);
            dailyCount.put("Wednesday", 0);
            dailyCount.put("Thursday", 0);
            dailyCount.put("Friday", 0);
        }

        @Override
        public String toString() {
            return code + " - " + faculty + " - " + subjectName;
        }

        boolean canScheduleTheory(String day) {
            return dailyCount.get(day) < maxPerDay;
        }

        void incrementDayCount(String day) {
            dailyCount.put(day, dailyCount.get(day) + 1);
        }

        void decrementDayCount(String day) {
            dailyCount.put(day, dailyCount.get(day) - 1);
        }
    }
}
