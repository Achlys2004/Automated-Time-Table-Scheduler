package com.university.scheduler.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

@Entity
public class TimetableEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String day;
    private int sessionNumber; // 1..11
    private String subject;

    public TimetableEntry() {
    }

    public TimetableEntry(String day, int sessionNumber, String subject) {
        this.day = day;
        this.sessionNumber = sessionNumber;
        this.subject = subject;
    }

    // Getters and setters

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
        this.sessionNumber = sessionNumber;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }
}
