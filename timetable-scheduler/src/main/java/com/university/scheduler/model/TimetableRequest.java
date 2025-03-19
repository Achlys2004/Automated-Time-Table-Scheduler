package com.university.scheduler.model;

import java.util.List;

public class TimetableRequest {
    private String department;
    private String semester;
    private Integer maxSessionsPerDay; // Maximum sessions per subject per day (e.g., 2)
    private List<Subject> subjects;
    private List<FacultyPreference> facultyPreferences;
    private List<String> availableTimeSlots;
    private List<String> breakTimes;

    public TimetableRequest() {
    }

    public TimetableRequest(String department, String semester, Integer maxSessionsPerDay, List<Subject> subjects,
                            List<FacultyPreference> facultyPreferences, List<String> availableTimeSlots,
                            List<String> breakTimes) {
        this.department = department;
        this.semester = semester;
        this.maxSessionsPerDay = maxSessionsPerDay;
        this.subjects = subjects;
        this.facultyPreferences = facultyPreferences;
        this.availableTimeSlots = availableTimeSlots;
        this.breakTimes = breakTimes;
    }

    public String getDepartment() {
        return department;
    }

    public void setDepartment(String department) {
        this.department = department;
    }

    public String getSemester() {
        return semester;
    }

    public void setSemester(String semester) {
        this.semester = semester;
    }

    public Integer getMaxSessionsPerDay() {
        return maxSessionsPerDay;
    }

    public void setMaxSessionsPerDay(Integer maxSessionsPerDay) {
        this.maxSessionsPerDay = maxSessionsPerDay;
    }

    public List<Subject> getSubjects() {
        return subjects;
    }

    public void setSubjects(List<Subject> subjects) {
        this.subjects = subjects;
    }

    public List<FacultyPreference> getFacultyPreferences() {
        return facultyPreferences;
    }

    public void setFacultyPreferences(List<FacultyPreference> facultyPreferences) {
        this.facultyPreferences = facultyPreferences;
    }

    public List<String> getAvailableTimeSlots() {
        return availableTimeSlots;
    }

    public void setAvailableTimeSlots(List<String> availableTimeSlots) {
        this.availableTimeSlots = availableTimeSlots;
    }

    public List<String> getBreakTimes() {
        return breakTimes;
    }

    public void setBreakTimes(List<String> breakTimes) {
        this.breakTimes = breakTimes;
    }
}
