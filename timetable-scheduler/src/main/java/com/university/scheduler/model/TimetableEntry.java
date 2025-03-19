package com.university.scheduler.model;

import jakarta.persistence.*;

@Entity
@Table(name = "timetable_entry")
public class TimetableEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String day;

    @Column(nullable = false)
    private int sessionNumber; // 1 to 8 (default number of time slots)

    @Column(nullable = false)
    private String subject;

    public TimetableEntry() {
    }

    public TimetableEntry(String day, int sessionNumber, String subject) {
        this.day = day;
        this.sessionNumber = sessionNumber;
        this.subject = subject;
    }

    public Long getId() {
        return id;
    }

    public String getDay() {
        return day;
    }

    public int getSessionNumber() {
        return sessionNumber;
    }

    public String getSubject() {
        return subject;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public void setDay(String day) {
        this.day = day;
    }

    public void setSessionNumber(int sessionNumber) {
        // Here we assume the number of time slots is 8.
        if (sessionNumber < 1 || sessionNumber > 9) {
            throw new IllegalArgumentException("Session number must be between 1 and 8");
        }
        this.sessionNumber = sessionNumber;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    @Override
    public String toString() {
        return "TimetableEntry{" +
                "id=" + id +
                ", day='" + day + '\'' +
                ", sessionNumber=" + sessionNumber +
                ", subject='" + subject + '\'' +
                '}';
    }
}
