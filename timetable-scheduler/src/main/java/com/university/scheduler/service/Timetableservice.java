package com.university.scheduler.service;

import com.university.scheduler.model.Subject;
import com.university.scheduler.model.TimetableEntry;
import com.university.scheduler.model.TimetableRequest;
import com.university.scheduler.repository.SubjectRepository;
import com.university.scheduler.repository.TimetableRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@Transactional
public class TimetableService {

    private final TimetableRepository timetableRepository;
    private final SubjectRepository subjectRepository;

    public TimetableService(TimetableRepository timetableRepository, SubjectRepository subjectRepository) {
        this.timetableRepository = timetableRepository;
        this.subjectRepository = subjectRepository;
    }

    /**
     * Improved greedy scheduling algorithm with extra filling.
     * Requirements:
     *  - Each subject has hoursPerWeek theory sessions.
     *  - Lab subjects get one lab block (3 consecutive slots) once per week.
     *  - Maximum one lab block per day.
     *  - Up to 2 theory sessions per subject per day (if 2, they must be consecutive).
     *  - Remaining free slots are "Free Period", but extra free slots (beyond desiredFreePeriods)
     *    are filled with additional sessions (bypassing max hours constraint).
     */
    public void generateSchedule(TimetableRequest request) {
        // Clear previous timetable entries.
        timetableRepository.deleteAll();
        System.out.println("Cleared existing timetable entries.");

        // Fetch subjects from request (ignoring department now).
        List<Subject> subjects = fetchSubjects(request);
        if (subjects.isEmpty()) {
            System.out.println("No subjects found for scheduling!");
            return;
        }
        System.out.println("Scheduling " + subjects.size() + " subjects...");

        // Build timetable: day -> array of 9 slots.
        String[] days = getDays();
        int slotsPerDay = 9;
        Map<String, TimetableEntry[]> timetableMap = new LinkedHashMap<>();
        for (String day : days) {
            TimetableEntry[] dailySlots = new TimetableEntry[slotsPerDay];
            for (int i = 0; i < slotsPerDay; i++) {
                TimetableEntry entry = new TimetableEntry();
                entry.setDay(day);
                entry.setSessionNumber(i + 1);
                entry.setSubject("Free Period");
                dailySlots[i] = entry;
            }
            timetableMap.put(day, dailySlots);
        }

        // Track sessions needed.
        Map<Subject, Integer> theoryNeeded = new HashMap<>();
        Map<Subject, Integer> labNeeded = new HashMap<>();
        for (Subject s : subjects) {
            theoryNeeded.put(s, s.getHoursPerWeek());
            labNeeded.put(s, s.isLabRequired() ? 3 : 0);
        }

        // Track whether a day already has a lab.
        Map<String, Boolean> dayHasLab = new HashMap<>();
        for (String day : days) {
            dayHasLab.put(day, false);
        }

        // Place lab blocks for subjects that need labs.
        for (Subject s : subjects) {
            if (labNeeded.get(s) == 3) {
                placeLabBlock(s, timetableMap, days, dayHasLab);
            }
        }

        // Distribute theory sessions.
        boolean stillPlacing = true;
        while (stillPlacing) {
            stillPlacing = false;
            for (Subject s : subjects) {
                if (theoryNeeded.get(s) > 0) {
                    boolean placedOne = placeOneTheorySession(s, timetableMap, days, theoryNeeded);
                    if (placedOne) {
                        stillPlacing = true;
                    }
                }
            }
        }

        // Fill extra free periods.
        int freeCount = countTotalFreePeriods(timetableMap);
        int desiredFree = request.getDesiredFreePeriods();
        System.out.println("Free periods before extra allocation: " + freeCount + ", desired: " + desiredFree);
        if (freeCount > desiredFree) {
            int extra = freeCount - desiredFree;
            fillExtraFreePeriods(timetableMap, extra, subjects);
        }

        // Save the timetable.
        List<TimetableEntry> finalEntries = new ArrayList<>();
        for (String day : days) {
            TimetableEntry[] dailySlots = timetableMap.get(day);
            Collections.addAll(finalEntries, dailySlots);
        }
        timetableRepository.saveAll(finalEntries);

        // Print final timetable.
        System.out.println("\n--- Final Timetable ---");
        for (String day : days) {
            System.out.print(day + ": ");
            TimetableEntry[] dailySlots = timetableMap.get(day);
            for (TimetableEntry entry : dailySlots) {
                System.out.print("[" + entry.getSessionNumber() + ": " + entry.getSubject() + "] ");
            }
            System.out.println();
        }
        System.out.println("Timetable generated successfully!");
    }

    private void placeLabBlock(Subject subject, Map<String, TimetableEntry[]> timetableMap,
                                 String[] days, Map<String, Boolean> dayHasLab) {
        for (String day : days) {
            if (dayHasLab.get(day)) continue;
            TimetableEntry[] slots = timetableMap.get(day);
            for (int i = 0; i <= slots.length - 3; i++) {
                if (isFree(slots, i, 3)) {
                    String labLabel = subject.getFaculty() + " - Lab " + subject.getName();
                    for (int j = i; j < i + 3; j++) {
                        slots[j].setSubject(labLabel);
                    }
                    dayHasLab.put(day, true);
                    System.out.println("Placed lab for " + subject.getName() + " on " + day +
                            " slots " + (i + 1) + "-" + (i + 3));
                    return;
                }
            }
        }
        System.out.println("Could not place lab block for " + subject.getName());
    }

    private boolean placeOneTheorySession(Subject subject, Map<String, TimetableEntry[]> timetableMap,
                                            String[] days, Map<Subject, Integer> theoryNeeded) {
        for (String day : days) {
            TimetableEntry[] slots = timetableMap.get(day);
            int sessionsToday = countSessionsForSubject(slots, subject);
            if (sessionsToday >= 2) continue;
            if (sessionsToday == 0) {
                for (int i = 0; i < slots.length; i++) {
                    if (slots[i].getSubject().equals("Free Period")) {
                        slots[i].setSubject(subject.getFaculty() + " - " + subject.getName());
                        theoryNeeded.put(subject, theoryNeeded.get(subject) - 1);
                        System.out.println("Placed theory for " + subject.getName() + " on " + day + " slot " + (i + 1));
                        return true;
                    }
                }
            } else {
                int firstIndex = findSubjectSlotIndex(slots, subject);
                if (firstIndex >= 0 && firstIndex < slots.length - 1) {
                    if (slots[firstIndex + 1].getSubject().equals("Free Period")) {
                        slots[firstIndex + 1].setSubject(subject.getFaculty() + " - " + subject.getName());
                        theoryNeeded.put(subject, theoryNeeded.get(subject) - 1);
                        System.out.println("Placed consecutive theory for " + subject.getName() + " on " + day + " slot " + (firstIndex + 2));
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean isFree(TimetableEntry[] slots, int start, int count) {
        for (int i = start; i < start + count; i++) {
            if (!slots[i].getSubject().equals("Free Period")) return false;
        }
        return true;
    }

    private int countSessionsForSubject(TimetableEntry[] slots, Subject subject) {
        String label = subject.getFaculty() + " - " + subject.getName();
        int count = 0;
        for (TimetableEntry entry : slots) {
            if (entry.getSubject().equals(label)) count++;
        }
        return count;
    }

    private int findSubjectSlotIndex(TimetableEntry[] slots, Subject subject) {
        String label = subject.getFaculty() + " - " + subject.getName();
        for (int i = 0; i < slots.length; i++) {
            if (slots[i].getSubject().equals(label)) return i;
        }
        return -1;
    }

    private int countTotalFreePeriods(Map<String, TimetableEntry[]> timetableMap) {
        int count = 0;
        for (TimetableEntry[] slots : timetableMap.values()) {
            for (TimetableEntry entry : slots) {
                if (entry.getSubject().equals("Free Period")) count++;
            }
        }
        return count;
    }

    private void fillExtraFreePeriods(Map<String, TimetableEntry[]> timetableMap, int extra, List<Subject> subjects) {
        System.out.println("Filling extra " + extra + " free periods with additional sessions...");
        List<SlotPosition> freeSlots = new ArrayList<>();
        for (Map.Entry<String, TimetableEntry[]> entry : timetableMap.entrySet()) {
            String day = entry.getKey();
            TimetableEntry[] slots = entry.getValue();
            for (int i = 0; i < slots.length; i++) {
                if (slots[i].getSubject().equals("Free Period")) {
                    freeSlots.add(new SlotPosition(day, i));
                }
            }
        }
        Collections.shuffle(freeSlots);
        int subjIndex = 0;
        for (int i = 0; i < extra && i < freeSlots.size(); i++) {
            SlotPosition pos = freeSlots.get(i);
            Subject subject = subjects.get(subjIndex);
            String label = subject.getFaculty() + " - " + subject.getName() + " (Extra)";
            timetableMap.get(pos.day)[pos.index].setSubject(label);
            subjIndex = (subjIndex + 1) % subjects.size();
            System.out.println("Filled extra slot on " + pos.day + " slot " + (pos.index + 1) + " with " + label);
        }
    }

    private static class SlotPosition {
        String day;
        int index;
        SlotPosition(String day, int index) {
            this.day = day;
            this.index = index;
        }
    }

    private List<Subject> fetchSubjects(TimetableRequest request) {
        // Use only subjects from the request.
        if (request.getSubjects() != null && !request.getSubjects().isEmpty()) {
            return request.getSubjects();
        }
        return subjectRepository.findAll();
    }

    public String[] getDays() {
        return new String[] {"Monday", "Tuesday", "Wednesday", "Thursday", "Friday"};
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

    public Map<String, List<String>> buildDaySlotMatrix() {
        List<TimetableEntry> entries = timetableRepository.findAll();
        Map<String, Map<Integer, String>> daySessionMap = new HashMap<>();
        for (TimetableEntry e : entries) {
            daySessionMap.computeIfAbsent(e.getDay(), d -> new HashMap<>())
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

    public List<TimetableEntry> getAllEntries() {
        return timetableRepository.findAll();
    }

    public void updateTeacherAvailability(String teacher, boolean available, String newTeacher, boolean updateOldTimetable) {
        List<TimetableEntry> entries = timetableRepository.findAll();
        for (TimetableEntry entry : entries) {
            if (entry.getSubject().contains(teacher)) {
                if (!available) {
                    if (newTeacher != null && !newTeacher.isEmpty()) {
                        String updatedSubject = entry.getSubject().replace(teacher, newTeacher);
                        entry.setSubject(updatedSubject);
                    } else {
                        entry.setSubject("Free Period");
                    }
                    timetableRepository.save(entry);
                }
            }
        }
        if (!updateOldTimetable) {
            timetableRepository.deleteAll();
            // Optionally re-generate the timetable.
        }
    }
}
