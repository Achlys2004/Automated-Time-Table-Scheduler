package com.university.scheduler.model;

import java.util.List;

public class FacultyPreference {
    private String faculty;
    private List<String> preferredDays;
    private List<String> preferredTime;

    public FacultyPreference() {
    }

    public FacultyPreference(String faculty, List<String> preferredDays) {
        this.faculty = faculty;
        this.preferredDays = preferredDays;
    }

    public String getFaculty() {
        return faculty;
    }

    public void setFaculty(String faculty) {
        this.faculty = faculty;
    }

    public List<String> getPreferredDays() {
        return preferredDays;
    }

    public void setPreferredDays(List<String> preferredDays) {
        this.preferredDays = preferredDays;
    }

    public List<String> getPreferredTime() {
        return preferredTime;
    }

    public void setPreferredTime(List<String> preferredTime) {
        this.preferredTime = preferredTime;
    }
}
