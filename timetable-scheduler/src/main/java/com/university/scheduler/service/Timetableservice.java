package com.university.scheduler.service;

import com.university.scheduler.model.Subject;
import com.university.scheduler.model.TimetableEntry;
import com.university.scheduler.model.TimetableRequest;
import com.university.scheduler.repository.SubjectRepository;
import com.university.scheduler.repository.TimetableRepository;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional
public class TimetableService {

    private static final String FREE_PERIOD = "Free Period";
    private static final String SHORT_BREAK = "Short Break (11:00-11:30)";
    private static final String LONG_BREAK = "Long Break (1:45-2:30)";
    private static final Logger logger = LoggerFactory.getLogger(TimetableService.class);

    private final TimetableRepository timetableRepository;
    private final SubjectRepository subjectRepository;

    private static final int MORNING_BREAK_INDEX = 3;
    private static final int AFTERNOON_BREAK_INDEX = 7;
    private static final int MAX_CONSECUTIVE_SESSIONS = 2;
    private static final int MAX_FREE_PERIODS_PER_DAY = 3;
    private static final int MAX_SESSIONS_PER_DAY = 2;

    public TimetableService(TimetableRepository timetableRepository, SubjectRepository subjectRepository) {
        this.timetableRepository = timetableRepository;
        this.subjectRepository = subjectRepository;
    }

    private int maxBacktrackingAttempts = 1000000;
    private int backtrackingAttempts = 0;

    private Map<String, com.university.scheduler.model.FacultyPreference> facultyPreferences = new HashMap<>();

    public void generateSchedule(TimetableRequest request) {
        timetableRepository.deleteAll();
        logger.info("Cleared existing timetable entries.");

        List<Subject> subjects = fetchSubjects(request);
        if (subjects.isEmpty()) {
            logger.warn("No subjects found for scheduling!");
            return;
        }
        logger.info("Scheduling {} subjects...", subjects.size());

        facultyPreferences.clear();
        if (request.getFacultyPreferences() != null && !request.getFacultyPreferences().isEmpty()) {
            for (com.university.scheduler.model.FacultyPreference pref : request.getFacultyPreferences()) {
                facultyPreferences.put(pref.getFaculty(), pref);
            }
            logger.info("Loaded {} faculty preferences", facultyPreferences.size());
        } else {
            logger.info("No faculty preferences provided - scheduling without faculty constraints");
        }

        String[] days = getDays();
        String[] timeSlots = getTimeSlots();
        int slotsPerDay = timeSlots.length;

        int totalSlots = days.length * slotsPerDay;
        int breakSlots = days.length * 2;
        int effectiveSlots = totalSlots - breakSlots;

        logger.info("Total slots: {}, Break slots: {}, Effective slots: {}", totalSlots, breakSlots, effectiveSlots);

        Map<String, TimetableEntry[]> timetableMap = new LinkedHashMap<>();
        for (String day : days) {
            TimetableEntry[] dailySlots = new TimetableEntry[slotsPerDay];
            for (int i = 0; i < slotsPerDay; i++) {
                TimetableEntry entry = new TimetableEntry();
                entry.setDay(day);
                entry.setSessionNumber(i + 1);

                if (i == MORNING_BREAK_INDEX) {
                    entry.setSubject(SHORT_BREAK);
                } else if (i == AFTERNOON_BREAK_INDEX) {
                    entry.setSubject(LONG_BREAK);
                } else {
                    entry.setSubject("UNALLOCATED");
                }

                dailySlots[i] = entry;
            }
            timetableMap.put(day, dailySlots);
        }

        // Set fixed hours per week for all subjects to 6
        Map<Subject, Integer> theoryNeeded = new HashMap<>();
        Map<Subject, Integer> labNeeded = new HashMap<>();
        for (Subject s : subjects) {
            // Override hoursPerWeek to ensure 6 slots per subject
            if (s.isLabRequired()) {
                // For lab subjects: 3 slots for lab, 3 slots for theory
                theoryNeeded.put(s, 3);
                labNeeded.put(s, 3);
            } else {
                // For theory-only subjects: 6 slots for theory
                theoryNeeded.put(s, 6);
                labNeeded.put(s, 0);
            }
        }

        int totalSubjectHours = subjects.size() * 6; // 6 slots per subject

        // Calculate free periods based on available slots
        int availableFreePeriods = effectiveSlots - totalSubjectHours;

        // Use either requested free periods or calculated available free periods
        int desiredFreePeriods = (request.getDesiredFreePeriods() != null)
                ? Math.min(request.getDesiredFreePeriods(), availableFreePeriods)
                : availableFreePeriods;

        logger.info("Total subject hours: {}, Available for free periods: {}, Using: {}",
                totalSubjectHours, availableFreePeriods, desiredFreePeriods);

        Map<String, Boolean> dayHasLab = new HashMap<>();
        for (String day : days) {
            dayHasLab.put(day, false);
        }

        for (Subject s : subjects) {
            if (s.isLabRequired()) {
                placeLabBlock(s, timetableMap, days, dayHasLab);
            }
        }

        List<WeightedSubject> weightedSubjects = new ArrayList<>();
        for (Subject s : subjects) {
            double weight = 1.0;
            if (s.isLabRequired())
                weight += 0.5;
            weight += (s.getHoursPerWeek() / 2.0);
            weightedSubjects.add(new WeightedSubject(s, weight));
        }

        Collections.shuffle(subjects);
        subjects.sort((a, b) -> {
            double weightA = weightedSubjects.stream()
                    .filter(ws -> ws.subject.getCode().equals(a.getCode()))
                    .findFirst().get().weight;
            double weightB = weightedSubjects.stream()
                    .filter(ws -> ws.subject.getCode().equals(b.getCode()))
                    .findFirst().get().weight;
            return Double.compare(weightB, weightA);
        });

        boolean stillPlacing = true;
        int staleIterations = 0;
        int maxStaleIterations = 5;

        while (stillPlacing && staleIterations < maxStaleIterations) {
            stillPlacing = false;

            List<Subject> roundSubjects = createDynamicPriorityList(subjects, theoryNeeded, timetableMap);

            for (Subject s : roundSubjects) {
                if (theoryNeeded.get(s) > 0) {
                    boolean placedOne = placeOneTheorySession(s, timetableMap, days, theoryNeeded);
                    if (placedOne) {
                        stillPlacing = true;
                        staleIterations = 0;
                    }
                }
            }

            if (!stillPlacing) {
                staleIterations++;
            }
        }

        int unallocatedCount = 0;
        for (TimetableEntry[] slots : timetableMap.values()) {
            for (TimetableEntry entry : slots) {
                if (entry.getSubject().equals("UNALLOCATED")) {
                    unallocatedCount++;
                }
            }
        }

        logger.info("Slots remaining unallocated: {}", unallocatedCount);

        int freePeriodsMade = 0;
        for (TimetableEntry[] slots : timetableMap.values()) {
            for (TimetableEntry entry : slots) {
                if (entry.getSubject().equals("UNALLOCATED")) {
                    if (freePeriodsMade < desiredFreePeriods) {
                        entry.setSubject(FREE_PERIOD);
                        freePeriodsMade++;
                    } else {
                        boolean allocated = false;
                        for (Subject s : subjects) {
                            if (theoryNeeded.get(s) > 0) {
                                entry.setSubject(s.getFaculty() + " - " + s.getName());
                                theoryNeeded.put(s, theoryNeeded.get(s) - 1);
                                allocated = true;
                                break;
                            }
                        }

                        if (!allocated) {
                            entry.setSubject(FREE_PERIOD);
                            freePeriodsMade++;
                        }
                    }
                }
            }
        }

        logger.info("Created {} free periods (desired: {})", freePeriodsMade, desiredFreePeriods);

        enforceExactFreePeriods(timetableMap, desiredFreePeriods, subjects);

        redistributeFreePeriods(timetableMap, days);

        logger.info("Final timetable - Free periods: {}", countTotalFreePeriods(timetableMap));

        validateAndFixConsecutiveSlots(timetableMap, subjects);

        validateAndEnsureAllHoursPlaced(timetableMap, subjects);

        List<TimetableEntry> finalEntries = new ArrayList<>();
        for (String day : days) {
            TimetableEntry[] dailySlots = timetableMap.get(day);
            Collections.addAll(finalEntries, dailySlots);
        }
        timetableRepository.saveAll(finalEntries);

        logger.info("\n--- Final Timetable ---");
        for (String day : days) {
            StringBuilder daySchedule = new StringBuilder(day + ": ");
            TimetableEntry[] dailySlots = timetableMap.get(day);
            for (TimetableEntry entry : dailySlots) {
                daySchedule.append("[").append(entry.getSessionNumber())
                        .append(": ").append(entry.getSubject()).append("] ");
            }
            logger.info(daySchedule.toString());
        }
        logger.info("Timetable generated successfully!");
    }

    public void generateBacktrackingSchedule(TimetableRequest request) {
        timetableRepository.deleteAll();
        logger.info("Cleared existing timetable entries");

        List<Subject> subjects = fetchSubjects(request);
        if (subjects.isEmpty()) {
            logger.warn("No subjects found for scheduling!");
            return;
        }

        facultyPreferences.clear();
        if (request.getFacultyPreferences() != null && !request.getFacultyPreferences().isEmpty()) {
            for (com.university.scheduler.model.FacultyPreference pref : request.getFacultyPreferences()) {
                facultyPreferences.put(pref.getFaculty(), pref);
            }
            logger.info("Loaded {} faculty preferences", facultyPreferences.size());
        } else {
            logger.info("No faculty preferences provided - scheduling without faculty constraints");
        }

        List<String> timeSlots = (request.getAvailableTimeSlots() == null ||
                request.getAvailableTimeSlots().isEmpty())
                        ? Arrays.asList(getTimeSlots())
                        : request.getAvailableTimeSlots();

        Set<String> breakTimes = (request.getBreakTimes() == null)
                ? new HashSet<>(Arrays.asList("11:00am - 11:30am", "1:45pm - 2:30pm"))
                : new HashSet<>(request.getBreakTimes());

        List<Slot> allSlots = new ArrayList<>();
        for (String day : getDays()) {
            for (int i = 0; i < timeSlots.size(); i++) {
                String ts = timeSlots.get(i);
                if (!breakTimes.contains(ts)) {
                    Slot slot = new Slot(day, i, ts);
                    allSlots.add(slot);
                }
            }
        }

        int maxPerDay = (request.getMaxSessionsPerDay() == null) ? 2 : request.getMaxSessionsPerDay();
        List<SubjectAllocation> subjectAllocs = new ArrayList<>();
        for (Subject s : subjects) {
            int theory = s.getHoursPerWeek();
            int lab = s.isLabRequired() ? 3 : 0;
            subjectAllocs.add(new SubjectAllocation(s.getCode(), s.getName(), s.getFaculty(),
                    theory, lab, maxPerDay));
        }

        List<TimetableEntry> solution = new ArrayList<>();
        backtrackingAttempts = 0;
        boolean success = backtrack(0, allSlots, subjectAllocs, new ArrayList<>(), solution);

        if (!success) {
            logger.warn("No valid timetable could be generated with the given constraints!");
        }
        timetableRepository.saveAll(solution);
        logger.info("Saved {} timetable entries to database", solution.size());
        logger.info("\n--- Final Backtracking Timetable ---");
        for (String day : getDays()) {
            List<TimetableEntry> dayEntries = solution.stream()
                    .filter(e -> e.getDay().equals(day))
                    .sorted(Comparator.comparing(TimetableEntry::getSessionNumber))
                    .toList();

            StringBuilder sb = new StringBuilder(day + ": ");
            for (TimetableEntry entry : dayEntries) {
                sb.append("[").append(entry.getSessionNumber())
                        .append(": ").append(entry.getSubject()).append("] ");
            }
            logger.info(sb.toString());
        }
    }

    private void placeLabBlock(Subject subject, Map<String, TimetableEntry[]> timetableMap,
            String[] days, Map<String, Boolean> dayHasLab) {

        if (!subject.isLabRequired()) {
            return;
        }

        String label = subject.getFaculty() + " - " + subject.getName() + " Lab";
        logger.info("Attempting to place lab block for {} with label {}", subject.getName(), label);

        List<String> shuffledDays = Arrays.asList(days.clone());
        Collections.shuffle(shuffledDays);

        for (String day : shuffledDays) {
            if (!dayHasLab.get(day)) {
                TimetableEntry[] slots = timetableMap.get(day);

                List<Integer> validStartPositions = new ArrayList<>();
                for (int i = 0; i < slots.length - 2; i++) {
                    if (i <= MORNING_BREAK_INDEX && MORNING_BREAK_INDEX < i + 3) {
                        continue;
                    }
                    if (i <= AFTERNOON_BREAK_INDEX && AFTERNOON_BREAK_INDEX < i + 3) {
                        continue;
                    }

                    if (isFree(slots, i, 3)) {
                        validStartPositions.add(i);
                    }
                }

                if (!validStartPositions.isEmpty()) {
                    Collections.shuffle(validStartPositions);
                    int startPos = validStartPositions.get(0);

                    for (int i = 0; i < 3; i++) {
                        slots[startPos + i].setSubject(label);
                    }

                    dayHasLab.put(day, true);
                    logger.info("Placed lab block for {} on {} at positions {}-{}",
                            subject.getName(), day, startPos, startPos + 2);
                    return;
                }
            }
        }

        for (String day : shuffledDays) {
            TimetableEntry[] slots = timetableMap.get(day);

            List<Integer> validStartPositions = new ArrayList<>();
            for (int i = 0; i < slots.length - 2; i++) {
                if (i <= MORNING_BREAK_INDEX && MORNING_BREAK_INDEX < i + 3) {
                    continue;
                }
                if (i <= AFTERNOON_BREAK_INDEX && AFTERNOON_BREAK_INDEX < i + 3) {
                    continue;
                }

                if (isFree(slots, i, 3)) {
                    validStartPositions.add(i);
                }
            }

            if (!validStartPositions.isEmpty()) {
                Collections.shuffle(validStartPositions);
                int startPos = validStartPositions.get(0);

                for (int i = 0; i < 3; i++) {
                    slots[startPos + i].setSubject(label);
                }

                dayHasLab.put(day, true);
                logger.info("Placed lab block for {} on {} at positions {}-{}",
                        subject.getName(), day, startPos, startPos + 2);
                return;
            }
        }

        logger.warn("Could not place lab block for {}", subject.getName());
    }

    private boolean placeOneTheorySession(Subject subject, Map<String, TimetableEntry[]> timetableMap,
            String[] days, Map<Subject, Integer> theoryNeeded) {

        if (theoryNeeded.get(subject) <= 0) {
            return false;
        }

        // Initialize day weights based on current allocations
        Map<String, Double> dayWeights = new HashMap<>();
        Map<String, Integer> subjectDayCount = new HashMap<>();
        String subjectLabel = subject.getFaculty() + " - " + subject.getName();

        // Check each day's current allocation for this subject
        for (String day : days) {
            TimetableEntry[] slots = timetableMap.get(day);
            int count = countSessionsForSubject(slots, subject);
            subjectDayCount.put(day, count);

            // If already at max for the day, give zero weight
            if (count >= MAX_SESSIONS_PER_DAY) {
                dayWeights.put(day, 0.0);
                continue;
            }

            // Prefer days with fewer sessions of this subject
            double weight = 10.0 - (count * 5.0);

            // Add slight randomness
            weight += (Math.random() * 2.0) - 1.0;

            // Count free slots to favor days with more free slots
            int freeSlots = 0;
            for (TimetableEntry slot : slots) {
                if (slot.getSubject().equals(FREE_PERIOD) || slot.getSubject().equals("UNALLOCATED")) {
                    freeSlots++;
                }
            }
            weight += (freeSlots * 0.2);

            dayWeights.put(day, Math.max(0, weight));
        }

        // Apply faculty preferences with higher weight
        if (facultyHasPreferences(subject.getFaculty())) {
            List<String> preferredDays = facultyPreferences.get(subject.getFaculty()).getPreferredDays();
            for (String day : preferredDays) {
                if (dayWeights.containsKey(day) && dayWeights.get(day) > 0) {
                    dayWeights.put(day, dayWeights.get(day) * 2.5);
                }
            }
        }

        // Create weighted random day selection
        List<String> weightedDays = new ArrayList<>();
        for (Map.Entry<String, Double> entry : dayWeights.entrySet()) {
            String day = entry.getKey();
            double weight = entry.getValue();
            int occurrences = (int) Math.ceil(weight);
            for (int i = 0; i < occurrences; i++) {
                weightedDays.add(day);
            }
        }

        if (weightedDays.isEmpty()) {
            return false;
        }

        Collections.shuffle(weightedDays);

        for (String day : weightedDays) {
            TimetableEntry[] slots = timetableMap.get(day);

            // Skip if already at max for this day
            if (subjectDayCount.get(day) >= MAX_SESSIONS_PER_DAY) {
                continue;
            }

            // Try to place consecutive slots if possible (preferred)
            if (theoryNeeded.get(subject) >= 2 && subjectDayCount.get(day) == 0) {
                // Try to place 2 consecutive slots
                List<Integer> consecutivePairs = new ArrayList<>();

                for (int i = 0; i < slots.length - 1; i++) {
                    // Skip slots around breaks
                    if (i == MORNING_BREAK_INDEX - 1 || i == MORNING_BREAK_INDEX ||
                            i == AFTERNOON_BREAK_INDEX - 1 || i == AFTERNOON_BREAK_INDEX) {
                        continue;
                    }

                    if (slots[i].getSubject().equals(FREE_PERIOD) &&
                            slots[i + 1].getSubject().equals(FREE_PERIOD)) {
                        consecutivePairs.add(i);
                    }
                }

                if (!consecutivePairs.isEmpty()) {
                    Collections.shuffle(consecutivePairs);
                    int startSlot = consecutivePairs.get(0);

                    // Place 2 consecutive slots
                    slots[startSlot].setSubject(subjectLabel);
                    slots[startSlot + 1].setSubject(subjectLabel);
                    theoryNeeded.put(subject, theoryNeeded.get(subject) - 2);

                    logger.info("Placed 2 consecutive theory sessions for {} on {} at slots {}-{}",
                            subject.getName(), day, startSlot + 1, startSlot + 2);
                    return true;
                }
            }

            // If consecutive placement failed or not needed, try single slot
            List<Integer> possibleSlots = new ArrayList<>();
            List<Double> slotWeights = new ArrayList<>();

            for (int i = 0; i < slots.length; i++) {
                if (slots[i].getSubject().equals(FREE_PERIOD)) {
                    boolean wouldViolateConsecutive = false;

                    // Check if this would create 3+ consecutive slots
                    int before = 0;
                    for (int j = i - 1; j >= 0; j--) {
                        if (slots[j].getSubject().equals(subjectLabel)) {
                            before++;
                        } else {
                            break;
                        }
                    }

                    int after = 0;
                    for (int j = i + 1; j < slots.length; j++) {
                        if (slots[j].getSubject().equals(subjectLabel)) {
                            after++;
                        } else {
                            break;
                        }
                    }

                    if (before + after + 1 > MAX_CONSECUTIVE_SESSIONS) {
                        wouldViolateConsecutive = true;
                    }

                    if (!wouldViolateConsecutive) {
                        possibleSlots.add(i);

                        // Weight morning slots slightly higher
                        double slotWeight = 1.0;
                        if (i < MORNING_BREAK_INDEX) {
                            slotWeight += 0.3;
                        }
                        slotWeight += (Math.random() - 0.5);
                        slotWeights.add(slotWeight);
                    }
                }
            }

            if (!possibleSlots.isEmpty()) {
                // Calculate weighted random selection
                double totalWeight = slotWeights.stream().mapToDouble(Double::doubleValue).sum();
                double randomValue = Math.random() * totalWeight;

                double cumulativeWeight = 0;
                int selectedIndex = 0;

                for (int i = 0; i < slotWeights.size(); i++) {
                    cumulativeWeight += slotWeights.get(i);
                    if (randomValue <= cumulativeWeight) {
                        selectedIndex = i;
                        break;
                    }
                }

                // Place subject in selected slot
                int slotIndex = possibleSlots.get(selectedIndex);
                slots[slotIndex].setSubject(subjectLabel);
                theoryNeeded.put(subject, theoryNeeded.get(subject) - 1);

                logger.info("Placed single theory session for {} on {} at slot {}",
                        subject.getName(), day, slotIndex + 1);
                return true;
            }
        }

        return false;
    }

    private boolean isFree(TimetableEntry[] slots, int start, int count) {
        if (start + count > slots.length) {
            return false;
        }

        for (int i = start; i < start + count; i++) {
            String subject = slots[i].getSubject();
            if (!subject.equals("UNALLOCATED") && !subject.equals(FREE_PERIOD)) {
                return false;
            }
        }

        return true;
    }

    private int countSessionsForSubject(TimetableEntry[] slots, Subject subject) {
        String label = subject.getFaculty() + " - " + subject.getName();
        int count = 0;
        for (TimetableEntry entry : slots) {
            if (entry.getSubject().equals(label)) {
                count++;
            }
        }
        return count;
    }

    private void redistributeFreePeriods(Map<String, TimetableEntry[]> timetableMap, String[] days) {
        int startingTotal = countTotalFreePeriods(timetableMap);
        Map<String, Integer> freePeriodCount = new HashMap<>();
        for (String day : days) {
            TimetableEntry[] slots = timetableMap.get(day);
            int count = 0;
            for (TimetableEntry entry : slots) {
                if (entry.getSubject().equals(FREE_PERIOD)) {
                    count++;
                }
            }
            freePeriodCount.put(day, count);
            logger.info("Day {} has {} free periods", day, count);
        }

        for (String day : days) {
            if (freePeriodCount.get(day) > MAX_FREE_PERIODS_PER_DAY) {
                int excess = freePeriodCount.get(day) - MAX_FREE_PERIODS_PER_DAY;
                logger.info("Day {} has {} excess free periods - redistributing", day, excess);
                for (String targetDay : days) {
                    if (freePeriodCount.get(targetDay) < MAX_FREE_PERIODS_PER_DAY && excess > 0) {
                        if (moveSubjectBetweenDays(timetableMap, day, targetDay)) {
                            freePeriodCount.put(day, freePeriodCount.get(day) - 1);
                            freePeriodCount.put(targetDay, freePeriodCount.get(targetDay) + 1);
                            excess--;
                            logger.info("Moved subject from {} to {}", day, targetDay);
                        }
                    }
                }
            }
        }
        int endingTotal = countTotalFreePeriods(timetableMap);
        if (startingTotal != endingTotal) {
            logger.warn("Free period count changed during redistribution! Start: {}, End: {}", startingTotal,
                    endingTotal);
        }
    }

    private boolean moveSubjectBetweenDays(Map<String, TimetableEntry[]> timetableMap,
            String sourceDay, String targetDay) {
        TimetableEntry[] sourceSlots = timetableMap.get(sourceDay);
        TimetableEntry[] targetSlots = timetableMap.get(targetDay);

        for (int i = 0; i < sourceSlots.length; i++) {
            String subject = sourceSlots[i].getSubject();
            if (!subject.equals(FREE_PERIOD) &&
                    !subject.equals(SHORT_BREAK) &&
                    !subject.equals(LONG_BREAK) &&
                    !subject.contains("Lab")) {
                for (int j = 0; j < targetSlots.length; j++) {
                    if (targetSlots[j].getSubject().equals(FREE_PERIOD)) {
                        String subjectToMove = sourceSlots[i].getSubject();
                        targetSlots[j].setSubject(subjectToMove);
                        sourceSlots[i].setSubject(FREE_PERIOD);
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private int countTotalFreePeriods(Map<String, TimetableEntry[]> timetableMap) {
        int count = 0;
        for (TimetableEntry[] slots : timetableMap.values()) {
            for (TimetableEntry entry : slots) {
                if (entry.getSubject().equals(FREE_PERIOD)) {
                    count++;
                }
            }
        }
        return count;
    }

    private void enforceExactFreePeriods(Map<String, TimetableEntry[]> timetableMap, int desiredFreePeriods,
            List<Subject> subjects) {
        int currentFree = countTotalFreePeriods(timetableMap);
        logger.info("Current free periods: {}, Desired: {}", currentFree, desiredFreePeriods);

        if (currentFree > desiredFreePeriods) {
            int excess = currentFree - desiredFreePeriods;
            fillExtraFreePeriods(timetableMap, excess, subjects);
        } else if (currentFree < desiredFreePeriods) {
            int needed = desiredFreePeriods - currentFree;
            logger.info("Need to add {} more free periods", needed);
            List<SlotPosition> possibleSlots = new ArrayList<>();
            for (String day : timetableMap.keySet()) {
                TimetableEntry[] slots = timetableMap.get(day);
                for (int i = 0; i < slots.length; i++) {
                    String subject = slots[i].getSubject();
                    if (!subject.equals(FREE_PERIOD) &&
                            !subject.equals(SHORT_BREAK) &&
                            !subject.equals(LONG_BREAK) &&
                            !subject.contains("Lab")) {
                        possibleSlots.add(new SlotPosition(day, i));
                    }
                }
            }

            Collections.shuffle(possibleSlots);
            for (int i = 0; i < needed && i < possibleSlots.size(); i++) {
                SlotPosition pos = possibleSlots.get(i);
                TimetableEntry[] slots = timetableMap.get(pos.day);
                slots[pos.index].setSubject(FREE_PERIOD);
                logger.info("Converted subject at {} to free period", pos);
            }
        }

        List<SlotPosition> unallocatedSlots = new ArrayList<>();
        for (String day : timetableMap.keySet()) {
            TimetableEntry[] slots = timetableMap.get(day);
            for (int i = 0; i < slots.length; i++) {
                if (slots[i].getSubject().equals("UNALLOCATED")) {
                    unallocatedSlots.add(new SlotPosition(day, i));
                }
            }
        }

        if (!unallocatedSlots.isEmpty()) {
            logger.info("Found {} unallocated slots to fill", unallocatedSlots.size());
            allocateRemainingSlots(timetableMap, unallocatedSlots, subjects);
        }

        int finalCount = countTotalFreePeriods(timetableMap);
        logger.info("After enforcement: {} free periods (target: {})", finalCount, desiredFreePeriods);

        if (finalCount != desiredFreePeriods) {
            logger.warn("Free period count still not exact! Forcing correction...");

            if (finalCount > desiredFreePeriods) {
                List<SlotPosition> freeSlots = new ArrayList<>();
                for (String day : timetableMap.keySet()) {
                    TimetableEntry[] slots = timetableMap.get(day);
                    for (int i = 0; i < slots.length; i++) {
                        if (slots[i].getSubject().equals(FREE_PERIOD)) {
                            freeSlots.add(new SlotPosition(day, i));
                        }
                    }
                }

                // Count subjects per day first
                Map<String, Map<String, Integer>> subjectCountByDay = new HashMap<>();
                for (Subject subject : subjects) {
                    String subjectLabel = subject.getFaculty() + " - " + subject.getName();
                    Map<String, Integer> dayCount = new HashMap<>();
                    for (String day : timetableMap.keySet()) {
                        dayCount.put(day, 0);
                    }
                    subjectCountByDay.put(subjectLabel, dayCount);
                }

                // Count existing allocations
                for (String day : timetableMap.keySet()) {
                    TimetableEntry[] dailySlots = timetableMap.get(day);
                    for (TimetableEntry entry : dailySlots) {
                        String subject = entry.getSubject();
                        if (!subject.equals(FREE_PERIOD) && !subject.equals(SHORT_BREAK) &&
                                !subject.equals(LONG_BREAK) && !subject.equals("UNALLOCATED")) {
                            String baseSubject = subject;
                            if (subject.endsWith(" Lab")) {
                                baseSubject = subject.substring(0, subject.length() - 4);
                            }

                            Map<String, Integer> dayCount = subjectCountByDay.get(baseSubject);
                            if (dayCount != null) {
                                dayCount.put(entry.getDay(), dayCount.get(entry.getDay()) + 1);
                            }
                        }
                    }
                }

                Collections.shuffle(freeSlots);
                int converted = 0;

                for (SlotPosition pos : freeSlots) {
                    if (converted >= finalCount - desiredFreePeriods)
                        break;

                    TimetableEntry[] slots = timetableMap.get(pos.day);

                    // Find subjects that don't exceed day limit
                    List<Subject> validSubjects = subjects.stream()
                            .filter(s -> {
                                String subjectLabel = s.getFaculty() + " - " + s.getName();
                                Map<String, Integer> dayCount = subjectCountByDay.get(subjectLabel);
                                return dayCount.get(pos.day) < MAX_CONSECUTIVE_SESSIONS;
                            })
                            .collect(Collectors.toList());

                    if (!validSubjects.isEmpty()) {
                        Subject fallbackSubject = validSubjects.get(new Random().nextInt(validSubjects.size()));
                        String subjectLabel = fallbackSubject.getFaculty() + " - " + fallbackSubject.getName();

                        // Check for consecutive constraint
                        boolean wouldViolateConsecutive = false;

                        if (pos.index >= 2 &&
                                slots[pos.index - 1].getSubject().equals(subjectLabel) &&
                                slots[pos.index - 2].getSubject().equals(subjectLabel)) {
                            wouldViolateConsecutive = true;
                        }

                        if (pos.index <= slots.length - 3 &&
                                slots[pos.index + 1].getSubject().equals(subjectLabel) &&
                                slots[pos.index + 2].getSubject().equals(subjectLabel)) {
                            wouldViolateConsecutive = true;
                        }

                        if (pos.index >= 1 && pos.index <= slots.length - 2 &&
                                slots[pos.index - 1].getSubject().equals(subjectLabel) &&
                                slots[pos.index + 1].getSubject().equals(subjectLabel)) {
                            wouldViolateConsecutive = true;
                        }

                        if (!wouldViolateConsecutive) {
                            slots[pos.index].setSubject(subjectLabel);

                            // Update the count
                            Map<String, Integer> dayCount = subjectCountByDay.get(subjectLabel);
                            dayCount.put(pos.day, dayCount.get(pos.day) + 1);

                            converted++;
                        }
                    } else {
                        // No valid subject found, use Additional Class
                        slots[pos.index].setSubject("Additional Class");
                        converted++;
                    }
                }
            } else {
                List<SlotPosition> nonFreeSlots = new ArrayList<>();
                for (String day : timetableMap.keySet()) {
                    TimetableEntry[] slots = timetableMap.get(day);
                    for (int i = 0; i < slots.length; i++) {
                        String subject = slots[i].getSubject();
                        if (!subject.equals(FREE_PERIOD) &&
                                !subject.equals(SHORT_BREAK) &&
                                !subject.equals(LONG_BREAK) &&
                                !subject.contains("Lab")) {
                            nonFreeSlots.add(new SlotPosition(day, i));
                        }
                    }
                }

                Collections.shuffle(nonFreeSlots);
                for (int i = 0; i < desiredFreePeriods - finalCount && i < nonFreeSlots.size(); i++) {
                    SlotPosition pos = nonFreeSlots.get(i);
                    TimetableEntry[] slots = timetableMap.get(pos.day);
                    slots[pos.index].setSubject(FREE_PERIOD);
                    logger.info("Forced conversion of subject at {} to free period", pos);
                }
            }
        }
    }

    private void fillExtraFreePeriods(Map<String, TimetableEntry[]> timetableMap, int extra, List<Subject> subjects) {
        logger.info("Need to fill {} extra free periods", extra);

        Map<Subject, Integer> remainingHours = new HashMap<>();
        for (Subject s : subjects) {
            int actualHours = countActualHours(timetableMap, s);
            int targetHours = s.getHoursPerWeek() + (s.isLabRequired() ? 3 : 0);

            if (actualHours < targetHours) {
                remainingHours.put(s, targetHours - actualHours);
                logger.info("Subject {} needs {} more hours", s.getName(), targetHours - actualHours);
            }
        }
        if (remainingHours.isEmpty()) {
            logger.warn("No subjects need more hours, can't fill {} extra free periods", extra);
            return;
        }

        List<SlotPosition> freeSlots = new ArrayList<>();
        String[] days = getDays();
        for (String day : days) {
            TimetableEntry[] slots = timetableMap.get(day);

            int freesInThisDay = 0;
            for (int i = 0; i < slots.length; i++) {
                if (slots[i].getSubject().equals(FREE_PERIOD)) {
                    freesInThisDay++;
                    freeSlots.add(new SlotPosition(day, i));
                }
            }
            logger.info("Day {} has {} free periods", day, freesInThisDay);
        }

        Collections.shuffle(freeSlots);
        int filled = 0;
        for (SlotPosition pos : freeSlots) {
            if (filled >= extra)
                break;

            List<Subject> needySubjects = new ArrayList<>(remainingHours.keySet());
            needySubjects.sort(Comparator.comparing(s -> -remainingHours.get(s)));

            if (!needySubjects.isEmpty()) {
                Subject subject = needySubjects.get(0);
                TimetableEntry[] slots = timetableMap.get(pos.day);
                slots[pos.index].setSubject(subject.getFaculty() + " - " + subject.getName());
                int remaining = remainingHours.get(subject) - 1;
                if (remaining <= 0) {
                    remainingHours.remove(subject);
                } else {
                    remainingHours.put(subject, remaining);
                }

                filled++;
                logger.info("Filled free period with {} at {} slot {}", subject.getName(), pos.day, pos.index + 1);
            }
        }

        logger.info("Filled {} out of {} extra free periods", filled, extra);
    }

    private int countActualHours(Map<String, TimetableEntry[]> timetableMap, Subject subject) {
        int count = 0;
        String theory = subject.getFaculty() + " - " + subject.getName();
        String lab = theory + " Lab";

        for (TimetableEntry[] slots : timetableMap.values()) {
            for (TimetableEntry entry : slots) {
                if (entry.getSubject().equals(theory) || entry.getSubject().equals(lab)) {
                    count++;
                }
            }
        }
        return count;
    }

    public String[] getDays() {
        return new String[] { "Monday", "Tuesday", "Wednesday", "Thursday", "Friday" };
    }

    public String[] getTimeSlots() {
        return new String[] {
                "8:45am - 9:45am",
                "9:45am - 10:15am",
                "10:15am - 11:00am",
                "11:00am - 11:30am",
                "11:30am - 12:15pm",
                "12:15pm - 1:00pm",
                "1:00pm - 1:45pm",
                "1:45pm - 2:30pm",
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
                row.add(sessionMap.getOrDefault(i, FREE_PERIOD));
            }
            matrix.put(day, row);
        }
        return matrix;
    }

    public List<TimetableEntry> getAllEntries() {
        return timetableRepository.findAll();
    }

    public void updateTeacherAvailability(String teacher, boolean available, String newTeacher,
            boolean updateOldTimetable) {
        List<TimetableEntry> entries = timetableRepository.findAll();
        List<TimetableEntry> updatedEntries = new ArrayList<>();
        for (TimetableEntry entry : entries) {
            if (entry.getSubject().contains(teacher)) {
                if (!available) {
                    if (newTeacher != null && !newTeacher.isEmpty()) {
                        String updatedSubject = entry.getSubject().replace(teacher, newTeacher);
                        entry.setSubject(updatedSubject);
                    } else {
                        entry.setSubject(FREE_PERIOD);
                    }
                    updatedEntries.add(entry);
                }
            }
        }
        if (!updatedEntries.isEmpty()) {
            timetableRepository.saveAll(updatedEntries);
            logger.info("Updated {} entries for teacher {}.", updatedEntries.size(), teacher);
        }

        if (!available && newTeacher != null && !newTeacher.isEmpty()) {
            List<Subject> subjects = subjectRepository.findAll();
            List<Subject> updatedSubjects = new ArrayList<>();
            for (Subject subject : subjects) {
                if (subject.getFaculty().equals(teacher)) {
                    subject.setFaculty(newTeacher);
                    updatedSubjects.add(subject);
                }
            }
            if (!updatedSubjects.isEmpty()) {
                subjectRepository.saveAll(updatedSubjects);
                logger.info("Updated {} subjects in repository for teacher {}.", updatedSubjects.size(), teacher);
            }
        }

        if (!updateOldTimetable) {
            timetableRepository.deleteAll();
            logger.info("Old timetable deleted as per update request.");
        }
    }

    private boolean backtrack(int slotIndex, List<Slot> allSlots,
            List<SubjectAllocation> subjectAllocs,
            List<TimetableEntry> partial,
            List<TimetableEntry> finalSolution) {

        backtrackingAttempts++;
        if (backtrackingAttempts > maxBacktrackingAttempts) {
            logger.warn("Maximum backtracking attempts reached ({}). Using best solution found so far.",
                    maxBacktrackingAttempts);
            finalSolution.addAll(partial);
            fillRemainingWithFreePeriods(slotIndex, allSlots, partial, finalSolution);
            return true;
        }

        if (backtrackingAttempts % 1000 == 0) {
            int remainingTheory = subjectAllocs.stream().mapToInt(s -> s.theoryLeft).sum();
            int remainingLab = subjectAllocs.stream().mapToInt(s -> s.labLeft).sum();
            logger.debug("Backtracking attempt #{}, depth: {}/{}; remaining: {} theory, {} lab sessions",
                    backtrackingAttempts, slotIndex, allSlots.size(), remainingTheory, remainingLab);
        }

        if (slotIndex < 3) {
            logger.debug("Detailed log for slot #{}", slotIndex);
            logger.debug("Current slot: {}", allSlots.get(slotIndex));
            logger.debug("Partial solution size: {}", partial.size());
            for (SubjectAllocation sa : subjectAllocs) {
                if (sa.theoryLeft > 0 || sa.labLeft > 0) {
                    logger.debug("Subject {}: {} theory, {} lab remaining", sa.subjectName, sa.theoryLeft, sa.labLeft);
                    for (Map.Entry<String, Integer> entry : sa.dailyCount.entrySet()) {
                        logger.debug("  {}: {}/{}", entry.getKey(), entry.getValue(), sa.maxPerDay);
                    }
                }
            }
        }

        List<SubjectAllocation> sortedSubjectAllocs = sortBySchedulingDifficulty(subjectAllocs);

        if (slotIndex == allSlots.size()) {
            boolean allAllocated = sortedSubjectAllocs.stream().allMatch(sa -> sa.theoryLeft == 0 && sa.labLeft == 0);
            if (allAllocated) {
                finalSolution.addAll(partial);
                return true;
            }
            return false;
        }

        Slot currentSlot = allSlots.get(slotIndex);
        logger.debug("Processing slot: {}", currentSlot);

        for (SubjectAllocation sa : sortedSubjectAllocs) {
            if (sa.theoryLeft > 0 && sa.canScheduleTheory(currentSlot.day)) {
                logger.debug("Trying theory for {}", sa.subjectName);
                sa.theoryLeft--;
                sa.incrementDayCount(currentSlot.day);
                TimetableEntry entry = new TimetableEntry();
                entry.setDay(currentSlot.day);
                entry.setSessionNumber(currentSlot.timeIndex + 1);
                entry.setSubject(sa.faculty + " - " + sa.subjectName);
                partial.add(entry);

                if (backtrack(slotIndex + 1, allSlots, sortedSubjectAllocs, partial, finalSolution)) {
                    return true;
                }
                partial.remove(partial.size() - 1);
                sa.decrementDayCount(currentSlot.day);
                sa.theoryLeft++;
                logger.debug("Backtracking from theory for {}", sa.subjectName);
            }

            if (sa.labLeft > 0 && sa.canScheduleTheory(currentSlot.day)) {
                logger.debug("Trying lab for {}", sa.subjectName);
                sa.labLeft--;
                sa.incrementDayCount(currentSlot.day);
                TimetableEntry entry = new TimetableEntry();
                entry.setDay(currentSlot.day);
                entry.setSessionNumber(currentSlot.timeIndex + 1);
                entry.setSubject(sa.faculty + " - " + sa.subjectName + " Lab");
                partial.add(entry);

                if (backtrack(slotIndex + 1, allSlots, sortedSubjectAllocs, partial, finalSolution)) {
                    return true;
                }
                partial.remove(partial.size() - 1);
                sa.decrementDayCount(currentSlot.day);
                sa.labLeft++;
                logger.debug("Backtracking from lab for {}", sa.subjectName);
            }
        }

        logger.debug("No subject could be scheduled for slot {}, using Free Period", currentSlot);
        TimetableEntry freeEntry = new TimetableEntry();
        freeEntry.setDay(currentSlot.day);
        freeEntry.setSessionNumber(currentSlot.timeIndex + 1);
        freeEntry.setSubject(FREE_PERIOD);
        partial.add(freeEntry);

        if (backtrack(slotIndex + 1, allSlots, sortedSubjectAllocs, partial, finalSolution)) {
            return true;
        }
        partial.remove(partial.size() - 1);
        logger.debug("Backtracking from Free Period");
        return false;
    }

    private void fillRemainingWithFreePeriods(int startIndex, List<Slot> allSlots,
            List<TimetableEntry> partial,
            List<TimetableEntry> finalSolution) {
        for (int i = startIndex; i < allSlots.size(); i++) {
            Slot slot = allSlots.get(i);
            TimetableEntry entry = new TimetableEntry();
            entry.setDay(slot.day);
            entry.setSessionNumber(slot.timeIndex + 1);
            entry.setSubject(FREE_PERIOD);
            partial.add(entry);
        }
        finalSolution.addAll(partial);
    }

    private List<SubjectAllocation> sortBySchedulingDifficulty(List<SubjectAllocation> subjects) {
        List<SubjectAllocation> sorted = new ArrayList<>(subjects);
        sorted.sort((a, b) -> {
            if (a.labLeft > 0 && b.labLeft == 0)
                return -1;
            if (a.labLeft == 0 && b.labLeft > 0)
                return 1;

            int aTotal = a.theoryLeft + a.labLeft;
            int bTotal = b.theoryLeft + b.labLeft;
            if (aTotal != bTotal) {
                return Integer.compare(bTotal, aTotal);
            }
            if (facultyPreferences != null && !facultyPreferences.isEmpty()) {
                boolean aHasPrefs = facultyHasPreferences(a.faculty);
                boolean bHasPrefs = facultyHasPreferences(b.faculty);
                if (aHasPrefs && !bHasPrefs)
                    return -1;
                if (!aHasPrefs && bHasPrefs)
                    return 1;
            }

            return 0;
        });
        return sorted;
    }

    private boolean facultyHasPreferences(String faculty) {
        return facultyPreferences.containsKey(faculty) &&
                facultyPreferences.get(faculty).getPreferredDays() != null &&
                !facultyPreferences.get(faculty).getPreferredDays().isEmpty();
    }

    private static class Slot {
        String day;
        int timeIndex;
        String timeText;

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

    private static class SubjectAllocation {
        String code;
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

    private List<Subject> fetchSubjects(TimetableRequest request) {
        if (request.getSubjects() != null && !request.getSubjects().isEmpty()) {
            logger.info("Using {} subjects from request", request.getSubjects().size());
            return request.getSubjects();
        } else {
            List<Subject> subjects = subjectRepository.findAll();
            logger.info("Fetched {} subjects from repository", subjects.size());
            return subjects;
        }
    }

    private static class SlotPosition {
        String day;
        int index;

        SlotPosition(String day, int index) {
            this.day = day;
            this.index = index;
        }

        @Override
        public String toString() {
            return day + " slot " + (index + 1);
        }
    }

    private void allocateRemainingSlots(Map<String, TimetableEntry[]> timetableMap,
            List<SlotPosition> slots,
            List<Subject> subjects) {

        // Set up tracking for subjects per day
        Map<String, Map<String, Integer>> subjectsPerDay = new HashMap<>();

        for (Subject s : subjects) {
            String label = s.getFaculty() + " - " + s.getName();
            Map<String, Integer> dayCounts = new HashMap<>();
            for (String day : timetableMap.keySet()) {
                dayCounts.put(day, 0);
            }
            subjectsPerDay.put(label, dayCounts);
        }

        // Count existing subjects per day
        for (String day : timetableMap.keySet()) {
            TimetableEntry[] daySlots = timetableMap.get(day);
            for (TimetableEntry slot : daySlots) {
                String subject = slot.getSubject();
                if (subject.endsWith(" Lab")) {
                    // Exclude labs from regular count
                    continue;
                }

                if (subjectsPerDay.containsKey(subject)) {
                    Map<String, Integer> counts = subjectsPerDay.get(subject);
                    counts.put(day, counts.get(day) + 1);
                }
            }
        }

        // Process slots to fill
        Collections.shuffle(slots);

        for (SlotPosition pos : slots) {
            TimetableEntry[] daySlots = timetableMap.get(pos.day);
            List<Subject> eligibleSubjects = new ArrayList<>();

            for (Subject s : subjects) {
                String label = s.getFaculty() + " - " + s.getName();

                // Skip if already at max per day
                if (subjectsPerDay.get(label).get(pos.day) >= MAX_SESSIONS_PER_DAY) {
                    continue;
                }

                // Check consecutive constraint
                boolean violatesConsecutive = false;

                // This is a sliding window approach to check all possible consecutive runs
                for (int start = Math.max(0, pos.index - MAX_CONSECUTIVE_SESSIONS + 1); start <= pos.index; start++) {

                    int consecutiveCount = 0;
                    for (int j = start; j < Math.min(daySlots.length, start + MAX_CONSECUTIVE_SESSIONS + 1); j++) {
                        if (j == pos.index || daySlots[j].getSubject().equals(label)) {
                            consecutiveCount++;
                            if (consecutiveCount > MAX_CONSECUTIVE_SESSIONS) {
                                violatesConsecutive = true;
                                break;
                            }
                        } else {
                            break; // Break on non-matching slot
                        }
                    }

                    if (violatesConsecutive) {
                        break;
                    }
                }

                if (!violatesConsecutive) {
                    eligibleSubjects.add(s);
                }
            }

            if (!eligibleSubjects.isEmpty()) {
                // Choose randomly from eligible subjects
                Collections.shuffle(eligibleSubjects);
                Subject chosen = eligibleSubjects.get(0);
                String label = chosen.getFaculty() + " - " + chosen.getName();

                daySlots[pos.index].setSubject(label);

                // Update counts
                Map<String, Integer> counts = subjectsPerDay.get(label);
                counts.put(pos.day, counts.get(pos.day) + 1);

                logger.info("Allocated {} to slot {} on {}", label, pos.index, pos.day);
            } else {
                // No eligible subjects - use a free period
                daySlots[pos.index].setSubject(FREE_PERIOD);
                logger.info("No eligible subjects for slot {} on {} - using free period",
                        pos.index, pos.day);
            }
        }
    }

    private static class WeightedSubject {
        Subject subject;
        double weight;

        WeightedSubject(Subject subject, double weight) {
            this.subject = subject;
            this.weight = weight;
        }
    }

    private List<Subject> createDynamicPriorityList(List<Subject> subjects,
            Map<Subject, Integer> theoryNeeded,
            Map<String, TimetableEntry[]> timetableMap) {

        Map<Subject, Set<String>> subjectDayCoverage = new HashMap<>();
        for (Subject s : subjects) {
            subjectDayCoverage.put(s, new HashSet<>());
        }

        for (String day : timetableMap.keySet()) {
            TimetableEntry[] slots = timetableMap.get(day);
            for (TimetableEntry entry : slots) {
                String subject = entry.getSubject();
                if (!subject.equals(FREE_PERIOD) &&
                        !subject.equals(SHORT_BREAK) &&
                        !subject.equals(LONG_BREAK) &&
                        !subject.equals("UNALLOCATED")) {

                    String[] parts = subject.split(" - ");
                    if (parts.length >= 2) {
                        final String faculty = parts[0];
                        String subjectName = parts[1];
                        final String finalName;

                        if (subjectName.endsWith(" Lab")) {
                            finalName = subjectName.substring(0, subjectName.length() - 4);
                        } else {
                            finalName = subjectName;
                        }

                        subjects.stream()
                                .filter(s -> s.getName().equals(finalName) && s.getFaculty().equals(faculty))
                                .findFirst()
                                .ifPresent(s -> subjectDayCoverage.get(s).add(day));
                    }
                }
            }
        }

        List<Subject> priorityList = new ArrayList<>(subjects);

        Collections.shuffle(priorityList);

        priorityList.sort((a, b) -> {
            int hoursRemainDiff = theoryNeeded.get(b) - theoryNeeded.get(a);
            if (hoursRemainDiff != 0)
                return hoursRemainDiff;

            int daysCoveredDiff = subjectDayCoverage.get(a).size() - subjectDayCoverage.get(b).size();
            if (daysCoveredDiff != 0)
                return daysCoveredDiff;

            return (int) (Math.random() * 5) - 2;
        });

        return priorityList;
    }

    private void validateAndFixConsecutiveSlots(Map<String, TimetableEntry[]> timetableMap, List<Subject> subjects) {
        logger.info("Validating and fixing any constraint violations");

        // Fix consecutive slot violations
        for (String day : timetableMap.keySet()) {
            TimetableEntry[] slots = timetableMap.get(day);

            // 1. Check for >2 consecutive slots of the same subject
            for (int i = 0; i < slots.length - 2; i++) {
                // Skip breaks
                if (i == MORNING_BREAK_INDEX - 1 || i == MORNING_BREAK_INDEX ||
                        i == AFTERNOON_BREAK_INDEX - 1 || i == AFTERNOON_BREAK_INDEX) {
                    continue;
                }

                String subject = slots[i].getSubject();

                // Skip free periods and lab subjects (allowed to have 3 consecutive slots)
                if (subject.equals(FREE_PERIOD) || subject.equals(SHORT_BREAK) ||
                        subject.equals(LONG_BREAK) || subject.endsWith(" Lab")) {
                    continue;
                }

                // Check if the 3 consecutive slots have the same subject
                if (subject.equals(slots[i + 1].getSubject()) && subject.equals(slots[i + 2].getSubject())) {
                    logger.warn("Found 3 consecutive sessions of {} on {} at positions {}-{}, fixing middle slot",
                            subject, day, i + 1, i + 3);
                    slots[i + 1].setSubject(FREE_PERIOD);
                }
            }

            // 2. Check for >2 slots of the same subject per day
            Map<String, Integer> subjectCounts = new HashMap<>();
            Map<String, List<Integer>> subjectPositions = new HashMap<>();

            for (int i = 0; i < slots.length; i++) {
                String subject = slots[i].getSubject();

                // Skip breaks and free periods
                if (subject.equals(FREE_PERIOD) || subject.equals(SHORT_BREAK) ||
                        subject.equals(LONG_BREAK)) {
                    continue;
                }

                // For lab subjects, count the base subject name
                String baseSubject = subject;
                if (subject.endsWith(" Lab")) {
                    baseSubject = subject.substring(0, subject.length() - 4);
                }

                subjectCounts.put(baseSubject, subjectCounts.getOrDefault(baseSubject, 0) + 1);

                if (!subjectPositions.containsKey(baseSubject)) {
                    subjectPositions.put(baseSubject, new ArrayList<>());
                }
                subjectPositions.get(baseSubject).add(i);
            }

            // Fix any subjects with >2 slots per day
            for (Map.Entry<String, Integer> entry : subjectCounts.entrySet()) {
                String subject = entry.getKey();
                int count = entry.getValue();

                if (count > MAX_SESSIONS_PER_DAY) {
                    logger.warn("Subject {} has {} slots on {}, reducing to {}",
                            subject, count, day, MAX_SESSIONS_PER_DAY);

                    List<Integer> positions = subjectPositions.get(subject);
                    // Sort to ensure we keep consecutive slots together if possible
                    Collections.sort(positions);

                    // Remove excess slots starting from the end
                    for (int i = positions.size() - 1; i >= MAX_SESSIONS_PER_DAY; i--) {
                        slots[positions.get(i)].setSubject(FREE_PERIOD);
                    }
                }
            }
        }
    }

    private void validateAndEnsureAllHoursPlaced(Map<String, TimetableEntry[]> timetableMap, List<Subject> subjects) {
        logger.info("Validating that all subjects have their required hours...");

        // Count actual hours placed for each subject
        Map<Subject, Integer> actualHoursPlaced = new HashMap<>();
        Map<Subject, Integer> requiredHours = new HashMap<>();

        // Initialize counters
        for (Subject s : subjects) {
            actualHoursPlaced.put(s, 0);
            if (s.isLabRequired()) {
                // Lab subjects: 3 hours lab + 3 hours theory = 6 total
                requiredHours.put(s, 6);
            } else {
                // Theory-only subjects: 6 hours theory
                requiredHours.put(s, 6);
            }
        }

        // Count actual hours placed
        for (String day : timetableMap.keySet()) {
            TimetableEntry[] slots = timetableMap.get(day);
            for (TimetableEntry slot : slots) {
                String slotSubject = slot.getSubject();
                if (slotSubject.equals(FREE_PERIOD) || slotSubject.equals(SHORT_BREAK) ||
                        slotSubject.equals(LONG_BREAK) || slotSubject.equals("UNALLOCATED")) {
                    continue;
                }

                // Extract subject name and faculty
                String baseSubject = slotSubject;
                if (baseSubject.endsWith(" Lab")) {
                    baseSubject = baseSubject.substring(0, baseSubject.length() - 4);
                }

                // Find matching subject in our list
                for (Subject s : subjects) {
                    String label = s.getFaculty() + " - " + s.getName();
                    if (baseSubject.equals(label)) {
                        actualHoursPlaced.put(s, actualHoursPlaced.get(s) + 1);
                        break;
                    }
                }
            }
        }

        // Find subjects with missing hours
        Map<Subject, Integer> missingHours = new HashMap<>();
        for (Subject s : subjects) {
            int placed = actualHoursPlaced.get(s);
            int required = requiredHours.get(s);

            if (placed < required) {
                missingHours.put(s, required - placed);
                logger.warn("Subject {} has only {} of {} required hours",
                        s.getName(), placed, required);
            }
        }

        if (missingHours.isEmpty()) {
            logger.info("All subjects have their required hours - timetable is complete");
            return;
        }

        // Find free periods that can be converted to subject hours
        List<SlotPosition> freeSlots = new ArrayList<>();
        for (String day : timetableMap.keySet()) {
            TimetableEntry[] slots = timetableMap.get(day);
            for (int i = 0; i < slots.length; i++) {
                if (slots[i].getSubject().equals(FREE_PERIOD)) {
                    freeSlots.add(new SlotPosition(day, i));
                }
            }
        }

        // Try to fill missing hours in a way that respects all constraints
        for (Map.Entry<Subject, Integer> entry : missingHours.entrySet()) {
            Subject subject = entry.getKey();
            int missing = entry.getValue();

            logger.info("Attempting to place {} missing hours for {}", missing, subject.getName());

            // Skip lab subjects that are missing lab hours - these need to be placed as a
            // block
            if (subject.isLabRequired() && missing == 3) {
                logger.warn("Subject {} is missing its lab block - this needs manual fixing", subject.getName());
                continue;
            }

            String subjectLabel = subject.getFaculty() + " - " + subject.getName();

            // Track days where this subject already has MAX_SESSIONS_PER_DAY slots
            Map<String, Integer> subjectCountPerDay = new HashMap<>();
            for (String day : timetableMap.keySet()) {
                subjectCountPerDay.put(day, 0);
                TimetableEntry[] slots = timetableMap.get(day);
                for (TimetableEntry slot : slots) {
                    if (slot.getSubject().equals(subjectLabel)) {
                        subjectCountPerDay.put(day, subjectCountPerDay.get(day) + 1);
                    }
                }
            }

            Collections.shuffle(freeSlots);
            Iterator<SlotPosition> it = freeSlots.iterator();

            while (it.hasNext() && missing > 0) {
                SlotPosition pos = it.next();

                // Skip if already at max sessions per day for this subject
                if (subjectCountPerDay.get(pos.day) >= MAX_SESSIONS_PER_DAY) {
                    continue;
                }

                TimetableEntry[] slots = timetableMap.get(pos.day);

                // Check consecutive constraint
                boolean wouldViolateConsecutive = false;

                // Check consecutive constraint
                int before = 0;
                for (int j = pos.index - 1; j >= 0; j--) {
                    if (slots[j].getSubject().equals(subjectLabel)) {
                        before++;
                    } else {
                        break;
                    }
                }

                int after = 0;
                for (int j = pos.index + 1; j < slots.length; j++) {
                    if (slots[j].getSubject().equals(subjectLabel)) {
                        after++;
                    } else {
                        break;
                    }
                }

                if (before + after + 1 > MAX_CONSECUTIVE_SESSIONS) {
                    wouldViolateConsecutive = true;
                }

                if (!wouldViolateConsecutive) {
                    // Place the subject
                    slots[pos.index].setSubject(subjectLabel);
                    missing--;
                    subjectCountPerDay.put(pos.day, subjectCountPerDay.get(pos.day) + 1);
                    it.remove(); // Remove this slot from available slots
                    logger.info("Placed additional hour for {} on {} at slot {}",
                            subject.getName(), pos.day, pos.index + 1);
                }
            }

            if (missing > 0) {
                logger.warn("Could not place all missing hours for {} - still missing {}",
                        subject.getName(), missing);
            }
        }
    }
}
