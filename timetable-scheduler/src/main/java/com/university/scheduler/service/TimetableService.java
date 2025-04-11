package com.university.scheduler.service;

import com.university.scheduler.model.Subject;
import com.university.scheduler.model.TimetableEntry;
import com.university.scheduler.model.TimetableRequest;
import com.university.scheduler.repository.SubjectRepository;
import com.university.scheduler.repository.TimetableRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional
public class TimetableService {

    private static final String FREE_PERIOD = "Free Period";
    private static final String SHORT_BREAK = "Short Break (11:00-11:30)";
    private static final String LONG_BREAK = "Long Break (1:45-2:30)";

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

        List<Subject> subjects = fetchSubjects(request);
        if (subjects.isEmpty()) {
            return;
        }

        facultyPreferences.clear();
        if (request.getFacultyPreferences() != null && !request.getFacultyPreferences().isEmpty()) {
            for (com.university.scheduler.model.FacultyPreference pref : request.getFacultyPreferences()) {
                facultyPreferences.put(pref.getFaculty(), pref);
            }
        }

        List<String> days = getDays();
        String[] timeSlots = getTimeSlots();
        int slotsPerDay = timeSlots.length;

        int totalSlots = days.size() * slotsPerDay;
        int breakSlots = days.size() * 2;
        int effectiveSlots = totalSlots - breakSlots;

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

        int totalSubjectHours = 0;
        for (Subject s : subjects) {
            totalSubjectHours += s.getHoursPerWeek();
            if (s.isLabRequired()) {
                totalSubjectHours += 3;
            }
        }

        int availableFreePeriods = effectiveSlots - totalSubjectHours;

        int desiredFreePeriods = (request.getDesiredFreePeriods() != null)
                ? Math.min(request.getDesiredFreePeriods(), availableFreePeriods)
                : availableFreePeriods;

        Map<String, Boolean> dayHasLab = new HashMap<>();
        for (String day : days) {
            dayHasLab.put(day, false);
        }

        for (Subject s : subjects) {
            if (s.isLabRequired()) {
                placeLabBlock(s, timetableMap, days.toArray(new String[0]), dayHasLab);
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
                    boolean placedOne = placeOneTheorySession(s, timetableMap, days.toArray(new String[0]),
                            theoryNeeded);
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

        for (TimetableEntry[] slots : timetableMap.values()) {
            for (TimetableEntry entry : slots) {
                if (entry.getSubject().equals("UNALLOCATED")) {
                    if (desiredFreePeriods > 0) {
                        entry.setSubject(FREE_PERIOD);
                        desiredFreePeriods--;
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
                        }
                    }
                }
            }
        }

        enforceExactFreePeriods(timetableMap, desiredFreePeriods, subjects);
        redistributeFreePeriods(timetableMap, days.toArray(new String[0]));
        validateAndFixConsecutiveSlots(timetableMap, subjects);
        validateLabBlocks(timetableMap, subjects);
        validateAndEnsureAllHoursPlaced(timetableMap, subjects);

        List<TimetableEntry> finalEntries = new ArrayList<>();
        for (String day : days) {
            TimetableEntry[] dailySlots = timetableMap.get(day);
            Collections.addAll(finalEntries, dailySlots);
        }
        timetableRepository.saveAll(finalEntries);
    }

    private void placeLabBlock(Subject subject, Map<String, TimetableEntry[]> timetableMap,
            String[] days, Map<String, Boolean> dayHasLab) {
        if (!subject.isLabRequired()) {
            return;
        }

        String label = subject.getFaculty() + " - " + subject.getName() + " Lab";

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
        Map<String, List<String>> stringTimetable = new HashMap<>();

        for (String day : days) {
            TimetableEntry[] entries = timetableMap.get(day);
            List<String> subjects = new ArrayList<>();
            for (TimetableEntry entry : entries) {
                subjects.add(entry.getSubject());
            }
            stringTimetable.put(day, subjects);
        }

        redistributeFreePeriods(stringTimetable);

        for (String day : days) {
            List<String> updatedSubjects = stringTimetable.get(day);
            TimetableEntry[] entries = timetableMap.get(day);
            for (int i = 0; i < entries.length; i++) {
                entries[i].setSubject(updatedSubjects.get(i));
            }
        }
    }

    private void redistributeFreePeriods(Map<String, List<String>> timetable) {
        Map<String, Integer> freePeriodCount = new HashMap<>();
        int totalFreePeriods = 0;

        for (String day : getDays()) {
            List<String> slots = timetable.get(day);
            int count = 0;
            for (String slot : slots) {
                if (slot.equals(FREE_PERIOD)) {
                    count++;
                    totalFreePeriods++;
                }
            }
            freePeriodCount.put(day, count);
        }

        int desiredFreePeriods = 9;

        if (totalFreePeriods != desiredFreePeriods) {
            adjustTotalFreePeriods(timetable, totalFreePeriods, desiredFreePeriods);

            freePeriodCount.clear();
            totalFreePeriods = 0;
            for (String day : getDays()) {
                List<String> slots = timetable.get(day);
                int count = 0;
                for (String slot : slots) {
                    if (slot.equals(FREE_PERIOD)) {
                        count++;
                        totalFreePeriods++;
                    }
                }
                freePeriodCount.put(day, count);
            }
        }

        for (String day : getDays()) {
            if (freePeriodCount.get(day) > MAX_FREE_PERIODS_PER_DAY) {
                redistributeExcessFreePeriods(timetable, day, freePeriodCount);
            }
        }

        balanceSubjectHours(timetable);
    }

    private void enforceExactFreePeriods(Map<String, TimetableEntry[]> timetableMap, int desiredFreePeriods,
            List<Subject> subjects) {
        int totalFreePeriods = 0;
        for (String day : timetableMap.keySet()) {
            TimetableEntry[] entries = timetableMap.get(day);
            for (TimetableEntry entry : entries) {
                if (entry.getSubject().equals(FREE_PERIOD)) {
                    totalFreePeriods++;
                }
            }
        }

        if (totalFreePeriods == desiredFreePeriods) {
            return;
        }

        if (totalFreePeriods < desiredFreePeriods) {
            int toAdd = desiredFreePeriods - totalFreePeriods;
            for (String day : timetableMap.keySet()) {
                if (toAdd <= 0)
                    break;
                TimetableEntry[] entries = timetableMap.get(day);
                for (int i = 0; i < entries.length; i++) {
                    if (toAdd <= 0)
                        break;
                    if (entries[i].getSubject().equals("UNALLOCATED")) {
                        entries[i].setSubject(FREE_PERIOD);
                        toAdd--;
                    }
                }
            }
        } else {
            int toRemove = totalFreePeriods - desiredFreePeriods;
            for (String day : timetableMap.keySet()) {
                if (toRemove <= 0)
                    break;
                TimetableEntry[] entries = timetableMap.get(day);
                for (int i = 0; i < entries.length; i++) {
                    if (toRemove <= 0)
                        break;
                    if (entries[i].getSubject().equals(FREE_PERIOD)) {
                        entries[i].setSubject("UNALLOCATED");
                        toRemove--;
                    }
                }
            }
        }
    }

    private void adjustTotalFreePeriods(Map<String, List<String>> timetable, int current, int target) {
        if (current < target) {
            addFreePeriods(timetable, target - current);
        } else if (current > target) {
            removeFreePeriods(timetable, current - target);
        }
    }

    private void addFreePeriods(Map<String, List<String>> timetable, int count) {
        Map<String, Integer> freePeriodCount = new HashMap<>();
        for (String day : getDays()) {
            List<String> slots = timetable.get(day);
            int dayCount = 0;
            for (String slot : slots) {
                if (slot.equals(FREE_PERIOD)) {
                    dayCount++;
                }
            }
            freePeriodCount.put(day, dayCount);
        }

        List<String> daysToAddTo = getDays().stream()
                .filter(day -> freePeriodCount.get(day) < MAX_FREE_PERIODS_PER_DAY)
                .sorted(Comparator.comparing(freePeriodCount::get))
                .collect(Collectors.toList());

        int added = 0;
        for (String day : daysToAddTo) {
            if (added >= count)
                break;

            List<String> slots = timetable.get(day);
            int canAdd = Math.min(MAX_FREE_PERIODS_PER_DAY - freePeriodCount.get(day), count - added);

            if (canAdd <= 0)
                continue;

            int currentAdded = 0;
            for (int i = 0; i < slots.size() && currentAdded < canAdd; i++) {
                if (slots.get(i).equals("UNALLOCATED")) {
                    slots.set(i, FREE_PERIOD);
                    currentAdded++;
                    added++;
                }
            }
        }
    }

    private void removeFreePeriods(Map<String, List<String>> timetable, int count) {
        Map<String, Integer> freePeriodCount = new HashMap<>();
        for (String day : getDays()) {
            List<String> slots = timetable.get(day);
            int dayCount = 0;
            for (String slot : slots) {
                if (slot.equals(FREE_PERIOD)) {
                    dayCount++;
                }
            }
            freePeriodCount.put(day, dayCount);
        }

        List<String> daysToRemoveFrom = getDays().stream()
                .sorted((a, b) -> freePeriodCount.get(b) - freePeriodCount.get(a))
                .collect(Collectors.toList());

        int removed = 0;
        for (String day : daysToRemoveFrom) {
            if (removed >= count)
                break;

            List<String> slots = timetable.get(day);
            int toRemove = Math.min(freePeriodCount.get(day), count - removed);

            if (toRemove <= 0)
                continue;

            int currentRemoved = 0;
            for (int i = 0; i < slots.size() && currentRemoved < toRemove; i++) {
                if (slots.get(i).equals(FREE_PERIOD)) {
                    slots.set(i, "UNALLOCATED");
                    currentRemoved++;
                    removed++;
                }
            }
        }
    }

    private void redistributeExcessFreePeriods(Map<String, List<String>> timetable, String sourceDay,
            Map<String, Integer> freePeriodCount) {
        int excess = freePeriodCount.get(sourceDay) - MAX_FREE_PERIODS_PER_DAY;
        if (excess <= 0)
            return;

        List<String> targetDays = getDays().stream()
                .filter(day -> !day.equals(sourceDay))
                .filter(day -> freePeriodCount.get(day) < MAX_FREE_PERIODS_PER_DAY)
                .sorted(Comparator.comparing(freePeriodCount::get))
                .collect(Collectors.toList());

        if (targetDays.isEmpty()) {
            convertExcessToSubjects(timetable, sourceDay, excess);
            return;
        }

        List<String> sourceSlots = timetable.get(sourceDay);
        List<Integer> freeIndices = new ArrayList<>();

        for (int i = 0; i < sourceSlots.size(); i++) {
            if (sourceSlots.get(i).equals(FREE_PERIOD)) {
                freeIndices.add(i);
            }
        }

        Collections.shuffle(freeIndices);

        int moved = 0;
        for (String targetDay : targetDays) {
            if (moved >= excess)
                break;

            List<String> targetSlots = timetable.get(targetDay);
            int spaceAvailable = MAX_FREE_PERIODS_PER_DAY - freePeriodCount.get(targetDay);
            int toMove = Math.min(spaceAvailable, excess - moved);

            if (toMove <= 0)
                continue;

            List<Integer> unallocatedIndices = new ArrayList<>();
            for (int i = 0; i < targetSlots.size(); i++) {
                if (targetSlots.get(i).equals("UNALLOCATED")) {
                    unallocatedIndices.add(i);
                }
            }

            if (unallocatedIndices.isEmpty())
                continue;

            int currentMoved = 0;
            for (int i = 0; i < Math.min(toMove, unallocatedIndices.size()); i++) {
                if (moved + currentMoved >= excess || moved + currentMoved >= freeIndices.size()) {
                    break;
                }

                int sourceIdx = freeIndices.get(moved + currentMoved);
                int targetIdx = unallocatedIndices.get(i);

                sourceSlots.set(sourceIdx, "UNALLOCATED");
                targetSlots.set(targetIdx, FREE_PERIOD);

                currentMoved++;
            }

            moved += currentMoved;
            freePeriodCount.put(sourceDay, freePeriodCount.get(sourceDay) - currentMoved);
            freePeriodCount.put(targetDay, freePeriodCount.get(targetDay) + currentMoved);
        }

        if (moved < excess) {
            convertExcessToSubjects(timetable, sourceDay, excess - moved);
        }
    }

    private void convertExcessToSubjects(Map<String, List<String>> timetable, String day, int excessCount) {
        List<String> slots = timetable.get(day);
        List<Integer> freeIndices = new ArrayList<>();

        for (int i = 0; i < slots.size(); i++) {
            if (slots.get(i).equals(FREE_PERIOD)) {
                freeIndices.add(i);
            }
        }

        Collections.shuffle(freeIndices);

        List<Subject> subjects = subjectRepository.findAll();
        Map<String, Integer> subjectHours = getSubjectHourCounts(timetable);

        for (int i = 0; i < Math.min(excessCount, freeIndices.size()); i++) {
            int index = freeIndices.get(i);

            String bestSubject = null;
            int maxDiff = 0;

            for (Subject subject : subjects) {
                String subjectName = subject.getFaculty() + " - " + subject.getName();
                int required = subject.getHoursPerWeek();
                int actual = subjectHours.getOrDefault(subjectName, 0);
                int diff = required - actual;

                if (diff > 0 && diff > maxDiff) {
                    maxDiff = diff;
                    bestSubject = subjectName;
                }
            }

            if (bestSubject != null) {
                slots.set(index, bestSubject);
                subjectHours.put(bestSubject, subjectHours.getOrDefault(bestSubject, 0) + 1);
            } else {
                for (int j = i; j < Math.min(excessCount, freeIndices.size()); j++) {
                    int idx = freeIndices.get(j);
                    slots.set(idx, "UNALLOCATED");
                }
                break;
            }
        }
    }

    private Map<String, Integer> getSubjectHourCounts(Map<String, List<String>> timetable) {
        Map<String, Integer> subjectHours = new HashMap<>();

        for (String day : getDays()) {
            List<String> slots = timetable.get(day);
            for (String slot : slots) {
                if (!slot.equals(FREE_PERIOD) && !slot.equals("UNALLOCATED") &&
                        !slot.equals(SHORT_BREAK) && !slot.equals(LONG_BREAK)) {
                    subjectHours.put(slot, subjectHours.getOrDefault(slot, 0) + 1);
                }
            }
        }

        return subjectHours;
    }

    private void balanceSubjectHours(Map<String, List<String>> timetable) {
        Map<String, Integer> subjectHours = new HashMap<>();

        for (String day : getDays()) {
            List<String> slots = timetable.get(day);
            for (String slot : slots) {
                if (!slot.equals(FREE_PERIOD) && !slot.equals("UNALLOCATED") &&
                        !slot.equals(SHORT_BREAK) && !slot.equals(LONG_BREAK)) {
                    subjectHours.put(slot, subjectHours.getOrDefault(slot, 0) + 1);
                }
            }
        }

        List<Subject> subjects = subjectRepository.findAll();

        for (String day : getDays()) {
            List<String> slots = timetable.get(day);
            for (int i = 0; i < slots.size(); i++) {
                if (slots.get(i).equals("UNALLOCATED")) {
                    boolean allocated = false;

                    for (Subject subject : subjects) {
                        String subjectName = subject.getFaculty() + " - " + subject.getName();
                        int required = subject.getHoursPerWeek();
                        int actual = subjectHours.getOrDefault(subjectName, 0);

                        if (actual < required) {
                            slots.set(i, subjectName);
                            subjectHours.put(subjectName, actual + 1);
                            allocated = true;
                            break;
                        }
                    }

                    if (!allocated) {
                        slots.set(i, FREE_PERIOD);
                    }
                }
            }
        }
    }

    public List<String> getDays() {
        return Arrays.asList("Monday", "Tuesday", "Wednesday", "Thursday", "Friday");
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
            }
        }

        if (!updateOldTimetable) {
            timetableRepository.deleteAll();
        }
    }

    public Map<String, Object> validateSchedule() {
        List<TimetableEntry> entries = timetableRepository.findAll();
        List<String> violations = new ArrayList<>();
        boolean isValid = true;

        Map<String, Integer> freePeriodsByDay = new HashMap<>();
        Map<String, Integer> subjectHours = new HashMap<>();
        Map<String, Integer> subjectLabHours = new HashMap<>();
        Map<String, Map<String, Integer>> daySubjectCount = new HashMap<>();

        for (String day : getDays()) {
            freePeriodsByDay.put(day, 0);
            daySubjectCount.put(day, new HashMap<>());
        }

        for (TimetableEntry entry : entries) {
            String day = entry.getDay();
            String subject = entry.getSubject();

            if (subject.equals(FREE_PERIOD)) {
                freePeriodsByDay.put(day, freePeriodsByDay.get(day) + 1);
                continue;
            }

            if (subject.equals(SHORT_BREAK) || subject.equals(LONG_BREAK)) {
                continue;
            }

            if (subject.contains("Lab")) {
                String baseSubject = subject.replace(" Lab", "");
                subjectLabHours.put(baseSubject, subjectLabHours.getOrDefault(baseSubject, 0) + 1);
            } else {
                subjectHours.put(subject, subjectHours.getOrDefault(subject, 0) + 1);
                daySubjectCount.get(day).put(subject, daySubjectCount.get(day).getOrDefault(subject, 0) + 1);
            }
        }

        int totalFreePeriods = freePeriodsByDay.values().stream().mapToInt(Integer::intValue).sum();
        if (totalFreePeriods != 9) {
            violations.add("Total free periods is " + totalFreePeriods + ", should be 9");
            isValid = false;
        }

        for (String day : getDays()) {
            int freePeriodsForDay = freePeriodsByDay.get(day);
            if (freePeriodsForDay > MAX_FREE_PERIODS_PER_DAY) {
                violations.add("Day " + day + " has " + freePeriodsForDay + " free periods, exceeding maximum of "
                        + MAX_FREE_PERIODS_PER_DAY);
                isValid = false;
            }

            Map<String, Integer> subjectCountForDay = daySubjectCount.get(day);
            for (Map.Entry<String, Integer> entry : subjectCountForDay.entrySet()) {
                if (entry.getValue() > MAX_SESSIONS_PER_DAY) {
                    violations.add("Subject " + entry.getKey() + " has " + entry.getValue() + " sessions on " + day
                            + ", exceeding maximum of " + MAX_SESSIONS_PER_DAY);
                    isValid = false;
                }
            }
        }

        List<Subject> subjects = subjectRepository.findAll();
        for (Subject subject : subjects) {
            String subjectName = subject.getFaculty() + " - " + subject.getName();
            int requiredHours = subject.getHoursPerWeek();
            int actualHours = subjectHours.getOrDefault(subjectName, 0);

            if (actualHours != requiredHours) {
                violations
                        .add("Subject " + subjectName + " has " + actualHours + " hours, should have " + requiredHours);
                isValid = false;
            }

            if (subject.isLabRequired()) {
                int requiredLabHours = 3;
                int actualLabHours = subjectLabHours.getOrDefault(subjectName, 0);

                if (actualLabHours != requiredLabHours) {
                    violations.add("Subject " + subjectName + " has " + actualLabHours + " lab hours, should have "
                            + requiredLabHours);
                    isValid = false;
                }
            }
        }

        Map<String, List<TimetableEntry>> entriesByDay = new HashMap<>();
        for (String day : getDays()) {
            entriesByDay.put(day, new ArrayList<>());
        }

        for (TimetableEntry entry : entries) {
            entriesByDay.get(entry.getDay()).add(entry);
        }

        for (String day : getDays()) {
            List<TimetableEntry> dayEntries = entriesByDay.get(day);
            dayEntries.sort(Comparator.comparing(TimetableEntry::getSessionNumber));

            for (int i = 0; i < dayEntries.size() - MAX_CONSECUTIVE_SESSIONS; i++) {
                String subject = dayEntries.get(i).getSubject();
                if (subject.equals(FREE_PERIOD) || subject.equals(SHORT_BREAK) ||
                        subject.equals(LONG_BREAK) || subject.contains("Lab")) {
                    continue;
                }

                boolean consecutive = true;
                for (int j = 1; j <= MAX_CONSECUTIVE_SESSIONS; j++) {
                    if (!dayEntries.get(i + j).getSubject().equals(subject)) {
                        consecutive = false;
                        break;
                    }
                }

                if (consecutive) {
                    violations.add("Subject " + subject + " has more than " + MAX_CONSECUTIVE_SESSIONS +
                            " consecutive sessions on " + day);
                    isValid = false;
                }
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("isValid", isValid);
        result.put("violations", violations);

        if (!isValid) {
            Map<String, List<String>> fixedTimetable = generateFixedTimetable(violations);
            result.put("fixedTimetable", fixedTimetable);
        }

        return result;
    }

    private Map<String, List<String>> generateFixedTimetable(List<String> violations) {
        Map<String, List<String>> daySlotMatrix = buildDaySlotMatrix();

        redistributeFreePeriods(daySlotMatrix);
        balanceSubjectHours(daySlotMatrix);
        fixConsecutiveSessions(daySlotMatrix);

        return daySlotMatrix;
    }

    private void fixConsecutiveSessions(Map<String, List<String>> timetable) {
        for (String day : getDays()) {
            List<String> slots = timetable.get(day);
            for (int i = 0; i < slots.size() - MAX_CONSECUTIVE_SESSIONS; i++) {
                String subject = slots.get(i);
                if (subject.equals(FREE_PERIOD) || subject.equals(SHORT_BREAK) ||
                        subject.equals(LONG_BREAK) || subject.contains("Lab")) {
                    continue;
                }

                boolean consecutive = true;
                for (int j = 1; j <= MAX_CONSECUTIVE_SESSIONS; j++) {
                    if (i + j >= slots.size() || !slots.get(i + j).equals(subject)) {
                        consecutive = false;
                        break;
                    }
                }

                if (consecutive) {
                    slots.set(i + MAX_CONSECUTIVE_SESSIONS, FREE_PERIOD);
                }
            }
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
            return request.getSubjects();
        } else {
            return subjectRepository.findAll();
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

                    List<Subject> otherSubjects = new ArrayList<>(subjects);
                    Collections.shuffle(otherSubjects);

                    boolean fixed = false;
                    for (Subject s : otherSubjects) {
                        String label = s.getFaculty() + " - " + s.getName();
                        if (!label.equals(slots[i].getSubject()) &&
                                countSessionsForSubject(slots, s) < MAX_SESSIONS_PER_DAY) {
                            slots[i + 2].setSubject(label);
                            fixed = true;
                            break;
                        }
                    }

                    if (!fixed) {
                        slots[i + 2].setSubject(FREE_PERIOD);
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
                    List<Integer> positions = subjectPositions.get(subject);
                    Collections.sort(positions);

                    for (int i = MAX_SESSIONS_PER_DAY; i < positions.size(); i++) {
                        slots[positions.get(i)].setSubject(FREE_PERIOD);
                    }
                }
            }
        }
    }

    private void validateLabBlocks(Map<String, TimetableEntry[]> timetableMap, List<Subject> subjects) {
        for (Subject subject : subjects) {
            if (!subject.isLabRequired()) {
                continue;
            }

            String labLabel = subject.getFaculty() + " - " + subject.getName() + " Lab";

            for (String day : timetableMap.keySet()) {
                TimetableEntry[] slots = timetableMap.get(day);
                int consecutiveCount = 0;

                for (int i = 0; i < slots.length; i++) {
                    if (slots[i].getSubject().equals(labLabel)) {
                        consecutiveCount++;
                    } else if (consecutiveCount > 0) {
                        break;
                    }
                }

                if (consecutiveCount == 3) {
                    break;
                }
            }
        }
    }

    private void validateAndEnsureAllHoursPlaced(Map<String, TimetableEntry[]> timetableMap, List<Subject> subjects) {
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
            }
        }

        if (missingHours.isEmpty()) {
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

            if (subject.isLabRequired() && missing == 3) {
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
                }
            }
        }
    }

    private boolean facultyHasPreferences(String faculty) {
        return facultyPreferences.containsKey(faculty) &&
                facultyPreferences.get(faculty).getPreferredDays() != null &&
                !facultyPreferences.get(faculty).getPreferredDays().isEmpty();
    }

    private int countActualHours(Map<String, TimetableEntry[]> timetableMap, Subject subject) {
        String subjectName = subject.getFaculty() + " - " + subject.getName();
        String labName = subjectName + " Lab";
        int count = 0;

        for (TimetableEntry[] dayEntries : timetableMap.values()) {
            for (TimetableEntry entry : dayEntries) {
                if (entry.getSubject().equals(subjectName)) {
                    count++;
                } else if (subject.isLabRequired() && entry.getSubject().equals(labName)) {
                    count++;
                }
            }
        }

        return count;
    }

}