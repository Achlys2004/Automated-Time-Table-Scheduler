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

        Map<Subject, Integer> theoryNeeded = new HashMap<>();
        Map<Subject, Integer> labNeeded = new HashMap<>();
        for (Subject s : subjects) {
            if (s.isLabRequired()) {
                theoryNeeded.put(s, s.getHoursPerWeek());
                labNeeded.put(s, 3);
            } else {
                theoryNeeded.put(s, s.getHoursPerWeek());
                labNeeded.put(s, 0);
            }
        }

        // Also update the totalSubjectHours calculation to include lab hours
        int totalSubjectHours = 0;
        for (Subject s : subjects) {
            totalSubjectHours += s.getHoursPerWeek();
            if (s.isLabRequired()) {
                totalSubjectHours += 3; // Add 3 extra hours for each lab
            }
        }

        int availableFreePeriods = effectiveSlots - totalSubjectHours;

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

        validateLabBlocks(timetableMap, subjects);

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

        validateLabBlocks(timetableMap, subjects);

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
                if (tryPlaceLabOnDay(subject, timetableMap, day, label)) {
                    dayHasLab.put(day, true);
                    return;
                }
            }
        }

        for (String day : shuffledDays) {
            if (tryPlaceLabOnDay(subject, timetableMap, day, label)) {
                dayHasLab.put(day, true);
                return;
            }
        }

        logger.warn("Could not place lab block for {}", subject.getName());
    }

    private boolean tryPlaceLabOnDay(Subject subject, Map<String, TimetableEntry[]> timetableMap,
            String day, String label) {
        TimetableEntry[] slots = timetableMap.get(day);

        List<Integer> validStartPositions = new ArrayList<>();
        for (int i = 0; i < slots.length - 2; i++) {
            if ((i <= MORNING_BREAK_INDEX && MORNING_BREAK_INDEX < i + 3) ||
                    (i <= AFTERNOON_BREAK_INDEX && AFTERNOON_BREAK_INDEX < i + 3)) {
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

            logger.info("Placed lab block for {} on {} at positions {}-{}",
                    subject.getName(), day, startPos + 1, startPos + 3);
            return true;
        }
        return false;
    }

    private boolean placeOneTheorySession(Subject subject, Map<String, TimetableEntry[]> timetableMap,
            String[] days, Map<Subject, Integer> theoryNeeded) {

        if (theoryNeeded.get(subject) <= 0) {
            return false;
        }

        Map<String, Double> dayWeights = new HashMap<>();
        Map<String, Integer> subjectDayCount = new HashMap<>();
        String subjectLabel = subject.getFaculty() + " - " + subject.getName();

        for (String day : days) {
            TimetableEntry[] slots = timetableMap.get(day);
            int count = countSessionsForSubject(slots, subject);
            subjectDayCount.put(day, count);

            if (count >= MAX_SESSIONS_PER_DAY) {
                dayWeights.put(day, 0.0);
                continue;
            }

            double weight = 10.0 - (count * 5.0);

            weight += (Math.random() * 2.0) - 1.0;

            int freeSlots = 0;
            for (TimetableEntry slot : slots) {
                if (slot.getSubject().equals("UNALLOCATED") || slot.getSubject().equals(FREE_PERIOD)) {
                    freeSlots++;
                }
            }

            weight += (freeSlots * 0.2);

            dayWeights.put(day, Math.max(0, weight));
        }

        if (facultyHasPreferences(subject.getFaculty())) {
            List<String> preferredDays = facultyPreferences.get(subject.getFaculty()).getPreferredDays();
            for (String day : preferredDays) {
                if (dayWeights.containsKey(day) && dayWeights.get(day) > 0) {
                    dayWeights.put(day, dayWeights.get(day) + 0.1);
                }
            }
        }

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

            if (subjectDayCount.get(day) >= MAX_SESSIONS_PER_DAY) {
                continue;
            }

            if (theoryNeeded.get(subject) >= 2 && subjectDayCount.get(day) == 0) {
                List<Integer> consecutivePairs = new ArrayList<>();

                for (int i = 0; i < slots.length - 1; i++) {
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

                    slots[startSlot].setSubject(subjectLabel);
                    slots[startSlot + 1].setSubject(subjectLabel);
                    theoryNeeded.put(subject, theoryNeeded.get(subject) - 2);

                    logger.info("Placed 2 consecutive theory sessions for {} on {} at slots {}-{}",
                            subject.getName(), day, startSlot + 1, startSlot + 2);
                    return true;
                }
            }

            List<Integer> possibleSlots = new ArrayList<>();
            List<Double> slotWeights = new ArrayList<>();

            for (int i = 0; i < slots.length; i++) {
                if (slots[i].getSubject().equals(FREE_PERIOD)) {
                    boolean wouldViolateConsecutive = false;

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
                logger.warn("Day {} has {} free periods, which exceeds maximum {}. Redistributing...",
                        day, freePeriodCount.get(day), MAX_FREE_PERIODS_PER_DAY);

                List<String> targetDays = new ArrayList<>();
                for (String otherDay : days) {
                    if (!otherDay.equals(day) && freePeriodCount.get(otherDay) < MAX_FREE_PERIODS_PER_DAY) {
                        targetDays.add(otherDay);
                    }
                }

                if (targetDays.isEmpty()) {
                    logger.warn("No suitable target days for redistribution");
                    continue;
                }

                Collections.shuffle(targetDays);

                int excessFreePeriods = freePeriodCount.get(day) - MAX_FREE_PERIODS_PER_DAY;
                for (String targetDay : targetDays) {
                    if (excessFreePeriods <= 0)
                        break;

                    if (moveSubjectBetweenDays(timetableMap, day, targetDay)) {
                        excessFreePeriods--;
                        freePeriodCount.put(day, freePeriodCount.get(day) - 1);
                        freePeriodCount.put(targetDay, freePeriodCount.get(targetDay) + 1);
                        logger.info("Moved a subject from {} to {}", day, targetDay);
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
                        int existingCount = 0;
                        for (TimetableEntry slot : targetSlots) {
                            if (slot.getSubject().equals(subject)) {
                                existingCount++;
                            }
                        }

                        if (existingCount < MAX_SESSIONS_PER_DAY) {
                            targetSlots[j].setSubject(subject);
                            sourceSlots[i].setSubject(FREE_PERIOD);
                            return true;
                        }
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

                            Map<String, Integer> dayCount = subjectCountByDay.get(subjectLabel);
                            dayCount.put(pos.day, dayCount.get(pos.day) + 1);
                            converted++;
                        }
                    } else {
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
                    slots[pos.index].setSubject(FREE_PERIOD);
                }
            }
        }
    }

    private void fillExtraFreePeriods(Map<String, TimetableEntry[]> timetableMap, int extra, List<Subject> subjects) {
        logger.info("Need to fill {} extra free periods", extra);

        Map<Subject, Integer> hourDifference = new HashMap<>();
        for (Subject s : subjects) {
            int actualHours = countActualHours(timetableMap, s);
            int targetHours = s.getHoursPerWeek();
            if (s.isLabRequired()) {
                targetHours += 3;
            }

            hourDifference.put(s, targetHours - actualHours);
        }

        List<Subject> overAllocated = hourDifference.entrySet().stream()
                .filter(entry -> entry.getValue() < 0)
                .map(Map.Entry::getKey)
                .sorted(Comparator.comparing(s -> hourDifference.get(s)))
                .collect(Collectors.toList());

        if (!overAllocated.isEmpty()) {
            int replaced = convertExcessHoursToFreePeriods(timetableMap, overAllocated, hourDifference,
                    Math.min(extra, -hourDifference.get(overAllocated.get(0))));
            extra -= replaced;
            logger.info("Converted {} excess hours to free periods", replaced);
        }

        List<Subject> underAllocated = hourDifference.entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .map(Map.Entry::getKey)
                .sorted(Comparator.comparing(s -> -hourDifference.get(s)))
                .collect(Collectors.toList());

        if (!underAllocated.isEmpty() && extra > 0) {
            allocateRemainingHours(timetableMap, underAllocated, hourDifference);
        }

        List<SlotPosition> freeSlots = new ArrayList<>();
        for (String day : getDays()) {
            TimetableEntry[] slots = timetableMap.get(day);
            for (int i = 0; i < slots.length; i++) {
                if (slots[i].getSubject().equals(FREE_PERIOD)) {
                    freeSlots.add(new SlotPosition(day, i));
                }
            }
        }

        if (extra > 0 && freeSlots.size() < extra) {
            createAdditionalFreePeriods(timetableMap, extra - freeSlots.size());
        }
    }

    private int convertExcessHoursToFreePeriods(Map<String, TimetableEntry[]> timetableMap,
            List<Subject> overAllocated,
            Map<Subject, Integer> hourDifference,
            int target) {
        int converted = 0;

        for (Subject subject : overAllocated) {
            if (converted >= target)
                break;

            String subjectLabel = subject.getFaculty() + " - " + subject.getName();
            int excess = -hourDifference.get(subject);

            Map<String, List<Integer>> dayPositions = new HashMap<>();
            for (String day : getDays()) {
                TimetableEntry[] slots = timetableMap.get(day);
                List<Integer> positions = new ArrayList<>();

                for (int i = 0; i < slots.length; i++) {
                    if (slots[i].getSubject().equals(subjectLabel)) {
                        positions.add(i);
                    }
                }

                if (!positions.isEmpty()) {
                    dayPositions.put(day, positions);
                }
            }

            List<String> daysWithSessions = new ArrayList<>(dayPositions.keySet());
            daysWithSessions.sort((a, b) -> dayPositions.get(b).size() - dayPositions.get(a).size());

            for (String day : daysWithSessions) {
                List<Integer> positions = dayPositions.get(day);
                if (positions.size() > 1) {
                    for (int pos : positions) {
                        if (converted < target && excess > 0) {
                            TimetableEntry[] slots = timetableMap.get(day);
                            slots[pos].setSubject(FREE_PERIOD);
                            converted++;
                            excess--;
                            hourDifference.put(subject, hourDifference.get(subject) + 1);
                            logger.info("Converted excess hour of {} on {} at position {} to free period",
                                    subject.getName(), day, pos + 1);

                            if (excess == 0)
                                break;
                        }
                    }
                }
            }
        }

        return converted;
    }

    private void allocateRemainingHours(Map<String, TimetableEntry[]> timetableMap,
            List<Subject> underAllocated,
            Map<Subject, Integer> hourDifference) {
        List<SlotPosition> freeSlots = new ArrayList<>();
        for (String day : getDays()) {
            TimetableEntry[] slots = timetableMap.get(day);
            for (int i = 0; i < slots.length; i++) {
                if (slots[i].getSubject().equals(FREE_PERIOD) || slots[i].getSubject().equals("UNALLOCATED")) {
                    freeSlots.add(new SlotPosition(day, i));
                }
            }
        }

        if (freeSlots.isEmpty()) {
            logger.warn("No free slots available to allocate remaining hours!");
            return;
        }

        Collections.shuffle(freeSlots);

        for (Subject subject : underAllocated) {
            int needed = hourDifference.get(subject);
            if (needed <= 0)
                continue;

            String subjectLabel = subject.getFaculty() + " - " + subject.getName();
            Map<String, Integer> dayCount = new HashMap<>();

            for (String day : getDays()) {
                TimetableEntry[] slots = timetableMap.get(day);
                int count = 0;
                for (TimetableEntry slot : slots) {
                    if (slot.getSubject().equals(subjectLabel)) {
                        count++;
                    }
                }
                dayCount.put(day, count);
            }

            Iterator<SlotPosition> it = freeSlots.iterator();
            while (it.hasNext() && needed > 0) {
                SlotPosition pos = it.next();
                String day = pos.day;

                if (dayCount.getOrDefault(day, 0) >= MAX_SESSIONS_PER_DAY) {
                    continue;
                }

                TimetableEntry[] slots = timetableMap.get(day);
                slots[pos.index].setSubject(subjectLabel);
                dayCount.put(day, dayCount.getOrDefault(day, 0) + 1);
                needed--;
                it.remove();

                logger.info("Allocated missing hour for {} on {} at position {}",
                        subject.getName(), day, pos.index + 1);
            }
        }
    }

    private void createAdditionalFreePeriods(Map<String, TimetableEntry[]> timetableMap, int needed) {
        List<SlotPosition> potentialSlots = new ArrayList<>();

        for (String day : getDays()) {
            TimetableEntry[] slots = timetableMap.get(day);
            Map<String, Integer> counts = new HashMap<>();

            for (TimetableEntry slot : slots) {
                String subject = slot.getSubject();
                if (subject.equals(SHORT_BREAK) || subject.equals(LONG_BREAK) ||
                        subject.equals(FREE_PERIOD) || subject.contains("Lab")) {
                    continue;
                }
                counts.put(subject, counts.getOrDefault(subject, 0) + 1);
            }

            for (Map.Entry<String, Integer> entry : counts.entrySet()) {
                if (entry.getValue() > MAX_SESSIONS_PER_DAY) {
                    String subject = entry.getKey();
                    for (int i = 0; i < slots.length; i++) {
                        if (slots[i].getSubject().equals(subject)) {
                            potentialSlots.add(new SlotPosition(day, i));
                        }
                    }
                }
            }
        }

        Collections.shuffle(potentialSlots);
        int converted = 0;

        for (SlotPosition pos : potentialSlots) {
            if (converted >= needed)
                break;

            TimetableEntry[] slots = timetableMap.get(pos.day);
            slots[pos.index].setSubject(FREE_PERIOD);
            converted++;

            logger.info("Created additional free period on {} at position {}",
                    pos.day, pos.index + 1);
        }

        logger.info("Created {} free periods out of {} needed", converted, needed);
    }

    private int countActualHours(Map<String, TimetableEntry[]> timetableMap, Subject subject) {
        int count = 0;
        String theory = subject.getFaculty() + " - " + subject.getName();
        String lab = theory + " Lab";

        for (TimetableEntry[] slots : timetableMap.values()) {
            for (TimetableEntry entry : slots) {
                if (entry.getSubject() != null && entry.getSubject().equals(theory)) {
                    count++;
                }
            }
        }

        boolean labFound = false;
        for (String day : timetableMap.keySet()) {
            TimetableEntry[] slots = timetableMap.get(day);
            for (TimetableEntry entry : slots) {
                if (entry.getSubject() != null && entry.getSubject().equals(lab)) {
                    labFound = true;
                    break;
                }
            }
            if (labFound) {
                count += 3;
                break;
            }
        }

        return count;
    }

    private void allocateRemainingSlots(Map<String, TimetableEntry[]> timetableMap,
            List<SlotPosition> unallocatedSlots,
            List<Subject> subjects) {
        logger.info("Allocating {} remaining unallocated slots", unallocatedSlots.size());

        Map<Subject, Integer> remainingHours = new HashMap<>();
        for (Subject subject : subjects) {
            int actualHours = countActualHours(timetableMap, subject);
            int targetHours = subject.getHoursPerWeek();
            if (subject.isLabRequired()) {
                targetHours += 3;
            }

            if (actualHours < targetHours) {
                remainingHours.put(subject, targetHours - actualHours);
                logger.info("Subject {} needs {} more hours", subject.getName(), targetHours - actualHours);
            }
        }

        List<Subject> needySubjects = new ArrayList<>(remainingHours.keySet());
        needySubjects.sort(Comparator.comparing(s -> -remainingHours.get(s)));

        int converted = 0;

        for (SlotPosition pos : unallocatedSlots) {
            TimetableEntry[] slots = timetableMap.get(pos.day);
            if (!slots[pos.index].getSubject().equals("UNALLOCATED")) {
                continue;
            }

            boolean allocated = false;
            for (Subject subject : needySubjects) {
                if (remainingHours.get(subject) > 0) {
                    String label = subject.getFaculty() + " - " + subject.getName();
                    slots[pos.index].setSubject(label);
                    remainingHours.put(subject, remainingHours.get(subject) - 1);
                    allocated = true;
                    logger.info("Allocated slot {} to {}", pos, subject.getName());
                    break;
                }
            }

            if (!allocated) {
                slots[pos.index].setSubject(FREE_PERIOD);
                converted++;
            }
        }

        if (converted > 0) {
            logger.info("Converted {} unallocated slots to free periods", converted);
        }
    }

    public String[] getDays() {
        return new String[] { "Monday", "Tuesday", "Wednesday", "Thursday", "Friday" };
    }

    public String[] getTimeSlots() {
        return new String[] {
                "8:45am - 9:30am",
                "9:30am - 10:15am",
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

    private static class WeightedSubject {
        Subject subject;
        double weight;

        WeightedSubject(Subject subject, double weight) {
            this.subject = subject;
            this.weight = weight;
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
        int converted = 0;  // Add this declaration

        for (String day : timetableMap.keySet()) {
            TimetableEntry[] slots = timetableMap.get(day);

            for (int i = 0; i < slots.length - 2; i++) {
                if (slots[i].getSubject().equals(SHORT_BREAK) ||
                        slots[i].getSubject().equals(LONG_BREAK)) {
                    continue;
                }

                if (!slots[i].getSubject().equals(FREE_PERIOD) &&
                        !slots[i].getSubject().contains("Lab") &&
                        slots[i].getSubject().equals(slots[i + 1].getSubject()) &&
                        slots[i].getSubject().equals(slots[i + 2].getSubject())) {

                    logger.warn("Found 3+ consecutive sessions of {} on {}. Fixing...",
                            slots[i].getSubject(), day);

                    List<Subject> otherSubjects = new ArrayList<>(subjects);
                    Collections.shuffle(otherSubjects);

                    boolean fixed = false;
                    for (Subject s : otherSubjects) {
                        String label = s.getFaculty() + " - " + s.getName();
                        if (!label.equals(slots[i].getSubject()) &&
                                countSessionsForSubject(slots, s) < MAX_SESSIONS_PER_DAY) {
                            slots[i + 2].setSubject(label);
                            fixed = true;
                            logger.info("Replaced third consecutive session with {}", label);
                            break;
                        }
                    }

                    if (!fixed) {
                        slots[i + 2].setSubject(FREE_PERIOD);
                        converted++;  // Add this increment
                    }
                }
            }

            Map<String, Integer> subjectCounts = new HashMap<>();
            Map<String, List<Integer>> subjectPositions = new HashMap<>();

            for (int i = 0; i < slots.length; i++) {
                String subject = slots[i].getSubject();
                if (!subject.equals(FREE_PERIOD) &&
                        !subject.equals(SHORT_BREAK) &&
                        !subject.equals(LONG_BREAK)) {

                    subjectCounts.put(subject, subjectCounts.getOrDefault(subject, 0) + 1);

                    subjectPositions.computeIfAbsent(subject, k -> new ArrayList<>()).add(i);
                }
            }

            for (Map.Entry<String, Integer> entry : subjectCounts.entrySet()) {
                String subject = entry.getKey();
                int count = entry.getValue();

                if (count > MAX_SESSIONS_PER_DAY && !subject.contains("Lab")) {
                    logger.warn("Subject {} has {} sessions on {}, max allowed is {}. Fixing...",
                            subject, count, day, MAX_SESSIONS_PER_DAY);

                    List<Integer> positions = subjectPositions.get(subject);
                    Collections.sort(positions);

                    for (int i = MAX_SESSIONS_PER_DAY; i < positions.size(); i++) {
                        slots[positions.get(i)].setSubject(FREE_PERIOD);
                        converted++;  // Add this increment
                    }
                }
            }
        }

        // Optional: Log how many slots were converted at the end
        if (converted > 0) {
            logger.info("Fixed {} constraint violations by converting to free periods", converted);
        }
    }

    private void validateLabBlocks(Map<String, TimetableEntry[]> timetableMap, List<Subject> subjects) {
        logger.info("Validating lab block allocations...");

        for (Subject subject : subjects) {
            if (!subject.isLabRequired()) {
                continue;
            }

            String labLabel = subject.getFaculty() + " - " + subject.getName() + " Lab";
            boolean labFound = false;
            boolean properBlockFound = false;

            for (String day : timetableMap.keySet()) {
                TimetableEntry[] slots = timetableMap.get(day);
                int consecutiveCount = 0;
                int startPos = -1;

                for (int i = 0; i < slots.length; i++) {
                    if (slots[i].getSubject().equals(labLabel)) {
                        labFound = true;
                        if (consecutiveCount == 0) {
                            startPos = i;
                        }
                        consecutiveCount++;
                    } else if (consecutiveCount > 0) {
                        break;
                    }
                }

                if (consecutiveCount == 3) {
                    properBlockFound = true;
                    logger.info("Found proper 3-hour lab block for {} on {} at slots {}-{}",
                            subject.getName(), day, startPos + 1, startPos + 3);
                    break;
                } else if (consecutiveCount > 0) {
                    logger.warn("Found incomplete lab block ({} hours) for {} on {}",
                            consecutiveCount, subject.getName(), day);
                }
            }

            if (!labFound) {
                logger.error("No lab session found for {}! This is a critical scheduling issue.",
                        subject.getName());
            } else if (!properBlockFound) {
                logger.error("Lab for {} does not have a proper 3-hour block! This is a critical issue.",
                        subject.getName());
            }
        }
    }

    private void validateAndEnsureAllHoursPlaced(Map<String, TimetableEntry[]> timetableMap, List<Subject> subjects) {
        logger.info("Validating that all subjects have their required hours...");

        Map<Subject, Integer> actualHoursPlaced = new HashMap<>();
        Map<Subject, Integer> requiredHours = new HashMap<>();

        for (Subject s : subjects) {
            actualHoursPlaced.put(s, countActualHours(timetableMap, s));

            if (s.isLabRequired()) {
                requiredHours.put(s, s.getHoursPerWeek() + 3);
            } else {
                requiredHours.put(s, s.getHoursPerWeek());
            }
        }

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

        List<SlotPosition> freeSlots = new ArrayList<>();
        for (String day : timetableMap.keySet()) {
            TimetableEntry[] slots = timetableMap.get(day);
            for (int i = 0; i < slots.length; i++) {
                if (slots[i].getSubject().equals(FREE_PERIOD)) {
                    freeSlots.add(new SlotPosition(day, i));
                }
            }
        }

        for (Map.Entry<Subject, Integer> entry : missingHours.entrySet()) {
            Subject subject = entry.getKey();
            int missing = entry.getValue();

            logger.info("Attempting to place {} missing hours for {}", missing, subject.getName());

            if (subject.isLabRequired() && missing == 3) {
                logger.warn("Subject {} is missing its lab block - this needs manual fixing", subject.getName());
                continue;
            }

            String subjectLabel = subject.getFaculty() + " - " + subject.getName();

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

                if (subjectCountPerDay.get(pos.day) >= MAX_SESSIONS_PER_DAY) {
                    continue;
                }

                TimetableEntry[] slots = timetableMap.get(pos.day);

                boolean wouldViolateConsecutive = false;

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
                    slots[pos.index].setSubject(subjectLabel);
                    missing--;
                    subjectCountPerDay.put(pos.day, subjectCountPerDay.get(pos.day) + 1);
                    it.remove();
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

    private boolean facultyHasPreferences(String faculty) {
        return facultyPreferences.containsKey(faculty) &&
                facultyPreferences.get(faculty).getPreferredDays() != null &&
                !facultyPreferences.get(faculty).getPreferredDays().isEmpty();
    }

}
