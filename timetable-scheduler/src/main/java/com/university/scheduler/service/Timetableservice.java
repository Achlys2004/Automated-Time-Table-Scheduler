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

    public TimetableService(TimetableRepository timetableRepository, SubjectRepository subjectRepository) {
        this.timetableRepository = timetableRepository;
        this.subjectRepository = subjectRepository;
    }

    private int maxBacktrackingAttempts = 10000;
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

        logger.info("Total slots: {}, Break slots: {}, Effective slots: {}",
                totalSlots, breakSlots, effectiveSlots);

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

        Map<Subject, Integer> theoryNeeded = new HashMap<>();
        Map<Subject, Integer> labNeeded = new HashMap<>();
        for (Subject s : subjects) {
            theoryNeeded.put(s, s.getHoursPerWeek());
            labNeeded.put(s, s.isLabRequired() ? 3 : 0);
        }

        int totalSubjectHours = 0;
        for (Subject s : subjects) {
            totalSubjectHours += s.getHoursPerWeek();
            if (s.isLabRequired()) {
                totalSubjectHours += 3;
            }
        }

        int desiredFreePeriods = request.getDesiredFreePeriods();

        if (totalSubjectHours + desiredFreePeriods > effectiveSlots) {
            logger.warn("Cannot fit all subjects and desired free periods! Adjusting free periods.");
            desiredFreePeriods = effectiveSlots - totalSubjectHours;
            if (desiredFreePeriods < 0) {
                logger.error("Cannot fit all subjects even with no free periods!");
                desiredFreePeriods = 0;
            }
        }

        logger.info("Total subject hours: {}, Desired free periods: {}, Available slots: {}",
                totalSubjectHours, desiredFreePeriods, effectiveSlots);

        Map<String, Boolean> dayHasLab = new HashMap<>();
        for (String day : days) {
            dayHasLab.put(day, false);
        }

        for (Subject s : subjects) {
            if (s.isLabRequired()) {
                placeLabBlock(s, timetableMap, days, dayHasLab);
            }
        }

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

        List<String> shuffledDays = Arrays.asList(days.clone());
        Collections.shuffle(shuffledDays);

        if (facultyHasPreferences(subject.getFaculty())) {
            List<String> preferredDays = facultyPreferences.get(subject.getFaculty()).getPreferredDays();

            List<String> orderedDays = new ArrayList<>();
            for (String day : days) {
                if (preferredDays.contains(day)) {
                    orderedDays.add(day);
                }
            }
            for (String day : days) {
                if (!preferredDays.contains(day)) {
                    orderedDays.add(day);
                }
            }
            shuffledDays = orderedDays;
        }

        String label = subject.getFaculty() + " - " + subject.getName();

        for (String day : shuffledDays) {
            TimetableEntry[] slots = timetableMap.get(day);

            int consecutiveCount = 0;
            int maxConsecutive = 0;
            for (TimetableEntry slot : slots) {
                if (slot.getSubject().equals(label)) {
                    consecutiveCount++;
                    maxConsecutive = Math.max(maxConsecutive, consecutiveCount);
                } else {
                    consecutiveCount = 0;
                }
            }

            if (maxConsecutive >= MAX_CONSECUTIVE_SESSIONS) {
                continue;
            }

            int existingSessionsToday = countSessionsForSubject(slots, subject);

            if (existingSessionsToday >= 2 && !day.equals("Friday")) {
                continue;
            }

            List<Integer> freeSlots = new ArrayList<>();
            for (int i = 0; i < slots.length; i++) {
                if (slots[i].getSubject().equals(FREE_PERIOD)) {
                    boolean wouldViolateConsecutive = false;

                    if (i > 0 && slots[i - 1].getSubject().equals(label)) {
                        if (i > 1 && slots[i - 2].getSubject().equals(label)) {
                            wouldViolateConsecutive = true;
                        }
                        if (i < slots.length - 1 && slots[i + 1].getSubject().equals(label)) {
                            wouldViolateConsecutive = true;
                        }
                    }
                    if (i < slots.length - 1 && slots[i + 1].getSubject().equals(label)) {
                        if (i < slots.length - 2 && slots[i + 2].getSubject().equals(label)) {
                            wouldViolateConsecutive = true;
                        }
                    }

                    if (!wouldViolateConsecutive) {
                        freeSlots.add(i);
                    }
                }
            }

            if (!freeSlots.isEmpty()) {
                Collections.shuffle(freeSlots);
                int slotIndex = freeSlots.get(0);

                slots[slotIndex].setSubject(label);

                theoryNeeded.put(subject, theoryNeeded.get(subject) - 1);

                logger.info("Placed theory session for {} on {} at slot {}",
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
            logger.warn("Free period count changed during redistribution! Start: {}, End: {}",
                    startingTotal, endingTotal);
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

                Collections.shuffle(freeSlots);
                for (int i = 0; i < finalCount - desiredFreePeriods; i++) {
                    SlotPosition pos = freeSlots.get(i);
                    TimetableEntry[] slots = timetableMap.get(pos.day);

                    if (!subjects.isEmpty()) {
                        Subject fallbackSubject = subjects.get(i % subjects.size());
                        slots[pos.index].setSubject(fallbackSubject.getFaculty() + " - " + fallbackSubject.getName());
                        logger.info("Forced conversion of free period at {} to {}",
                                pos, fallbackSubject.getName());
                    } else {
                        slots[pos.index].setSubject("Additional Class");
                        logger.info("Forced conversion of free period at {} to Additional Class", pos);
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
                logger.info("Filled free period with {} at {} slot {}",
                        subject.getName(), pos.day, pos.index + 1);
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
        if (slots.isEmpty()) {
            return;
        }

        logger.info("Allocating {} remaining slots to subjects with constraints", slots.size());
        Collections.shuffle(slots);

        Map<String, Map<String, Integer>> subjectCountByDay = new HashMap<>();
        for (Subject subject : subjects) {
            String subjectLabel = subject.getFaculty() + " - " + subject.getName();

            Map<String, Integer> dayCount = new HashMap<>();
            for (String day : timetableMap.keySet()) {
                dayCount.put(day, 0);
            }
            subjectCountByDay.put(subjectLabel, dayCount);
        }
        for (String day : timetableMap.keySet()) {
            TimetableEntry[] dailySlots = timetableMap.get(day);
            for (TimetableEntry entry : dailySlots) {
                String subject = entry.getSubject();

                String baseSubject = subject;
                if (subject.endsWith(" Lab")) {
                    baseSubject = subject.substring(0, subject.length() - 4);
                }

                if (subjectCountByDay.containsKey(baseSubject)) {
                    Map<String, Integer> dayCount = subjectCountByDay.get(baseSubject);
                    dayCount.put(day, dayCount.get(day) + 1);
                }
            }
        }

        for (SlotPosition pos : slots) {
            List<Subject> shuffledSubjects = new ArrayList<>(subjects);
            Collections.shuffle(shuffledSubjects);

            boolean allocated = false;

            for (Subject subject : shuffledSubjects) {
                String subjectLabel = subject.getFaculty() + " - " + subject.getName();
                Map<String, Integer> dayCount = subjectCountByDay.getOrDefault(subjectLabel, new HashMap<>());

                int currentCount = dayCount.getOrDefault(pos.day, 0);
                if (currentCount < 2) {
                    TimetableEntry[] dailySlots = timetableMap.get(pos.day);

                    boolean isLab = false;
                    if (subject.isLabRequired() && pos.index + 2 < dailySlots.length) {
                        if (pos.index + 1 < dailySlots.length &&
                                pos.index + 2 < dailySlots.length &&
                                dailySlots[pos.index + 1].getSubject().equals("UNALLOCATED") &&
                                dailySlots[pos.index + 2].getSubject().equals("UNALLOCATED")) {
                            isLab = true;
                        }
                    }

                    if (isLab) {
                        String labLabel = subjectLabel + " Lab";
                        dailySlots[pos.index].setSubject(labLabel);
                        dailySlots[pos.index + 1].setSubject(labLabel);
                        dailySlots[pos.index + 2].setSubject(labLabel);

                        dayCount.put(pos.day, currentCount + 1);
                        subjectCountByDay.put(subjectLabel, dayCount);

                        logger.info("Assigned lab block at {} to {} (now has {} sessions that day)",
                                pos, subject.getName(), currentCount + 1);

                        slots.removeIf(p -> p.day.equals(pos.day) &&
                                (p.index == pos.index + 1 || p.index == pos.index + 2));
                    } else {
                        dailySlots[pos.index].setSubject(subjectLabel);

                        dayCount.put(pos.day, currentCount + 1);
                        subjectCountByDay.put(subjectLabel, dayCount);

                        logger.info("Assigned extra slot at {} to {} (now has {} sessions that day)",
                                pos, subject.getName(), currentCount + 1);
                    }

                    allocated = true;
                    break;
                }
            }
            if (!allocated) {
                if (!subjects.isEmpty()) {
                    Subject fallbackSubject = subjects.get(new Random().nextInt(subjects.size()));
                    String subjectLabel = fallbackSubject.getFaculty() + " - " + fallbackSubject.getName();

                    TimetableEntry[] dailySlots = timetableMap.get(pos.day);
                    dailySlots[pos.index].setSubject(subjectLabel);
                    Map<String, Integer> dayCount = subjectCountByDay.getOrDefault(subjectLabel, new HashMap<>());
                    int currentCount = dayCount.getOrDefault(pos.day, 0);
                    dayCount.put(pos.day, currentCount + 1);
                    subjectCountByDay.put(subjectLabel, dayCount);

                    logger.info("Assigned fallback slot at {} to {} (now has {} sessions that day, exceeding limit)",
                            pos, fallbackSubject.getName(), currentCount + 1);
                } else {
                    TimetableEntry[] dailySlots = timetableMap.get(pos.day);
                    dailySlots[pos.index].setSubject(FREE_PERIOD);
                    logger.info("No subjects available, marked {} as Free Period", pos);
                }
            }
        }
    }
}
