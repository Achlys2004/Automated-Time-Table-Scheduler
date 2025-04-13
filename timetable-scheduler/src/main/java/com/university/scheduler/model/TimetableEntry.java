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
    private int sessionNumber;

    @Column(nullable = false)
    private String subject;

    @Column(name = "is_lab_session")
    private Boolean isLabSession = false;  // Initialize with default value

    public TimetableEntry() {
    }

    public TimetableEntry(String day, int sessionNumber, String subject) {
        this.day = day;
        this.sessionNumber = sessionNumber;
        this.subject = subject;
        this.isLabSession = false;  // Set default value in constructor
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

    public Boolean getIsLabSession() {
        return isLabSession;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public void setDay(String day) {
        this.day = day;
    }

    public void setSessionNumber(int sessionNumber) {
        // Fix the comment and validation to match actual time slots (11)
        if (sessionNumber < 1 || sessionNumber > 11) {
            throw new IllegalArgumentException("Session number must be between 1 and 11");
        }
        this.sessionNumber = sessionNumber;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public void setIsLabSession(Boolean labSession) {
        this.isLabSession = labSession;
    }

    public boolean isLabSession() {
        return this.subject != null && this.subject.contains("Lab");
    }

    @Override
    public String toString() {
        return "TimetableEntry{" +
                "id=" + id +
                ", day='" + day + '\'' +
                ", sessionNumber=" + sessionNumber +
                ", subject='" + subject + '\'' +
                ", isLabSession=" + isLabSession +
                '}';
    }
}
