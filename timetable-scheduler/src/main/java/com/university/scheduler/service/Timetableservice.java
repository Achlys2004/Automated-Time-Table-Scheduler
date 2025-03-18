package com.university.scheduler.service;

import com.university.scheduler.model.TimetableEntry;
import com.university.scheduler.repository.TimetableRepository;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class TimetableService {
    private final TimetableRepository repository;
    
    public TimetableService(TimetableRepository repository) {
        this.repository = repository;
    }

    private static final String[] DAYS = {"Monday", "Tuesday", "Wednesday", "Thursday", "Friday"};
    private static final int SLOTS_PER_DAY = 8;

    public List<TimetableEntry> generateSchedule(Map<String, Integer> subjectSessions) {
        List<TimetableEntry> schedule = new ArrayList<>();
        int slotIndex = 0;

        for (Map.Entry<String, Integer> entry : subjectSessions.entrySet()) {
            String subject = entry.getKey();
            int sessions = entry.getValue();

            for (int i = 0; i < sessions; i++) {
                int dayIndex = slotIndex / SLOTS_PER_DAY;
                int sessionNumber = slotIndex % SLOTS_PER_DAY + 1;

                schedule.add(new TimetableEntry(DAYS[dayIndex], sessionNumber, subject));
                slotIndex = (slotIndex + 2) % (DAYS.length * SLOTS_PER_DAY); // Avoid consecutive same subjects
            }
        }

        return repository.saveAll(schedule);
    }

    public List<TimetableEntry> getTimetable() {
        return repository.findAll();
    }
}
