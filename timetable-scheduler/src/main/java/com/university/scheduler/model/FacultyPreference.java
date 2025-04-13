package com.university.scheduler.model;

import jakarta.persistence.*;
import java.util.List;

@Entity
@Table(name = "faculty_preferences")
public class FacultyPreference {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String faculty;

    @ElementCollection
    @CollectionTable(name = "faculty_preferred_days")
    private List<String> preferredDays;

    @ElementCollection
    @CollectionTable(name = "faculty_preferred_times")
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

    // Define the isPreferenceMet() method
    public boolean isPreferenceMet() {
        // Implement logic to check if the preference is met
        // For example, return true if the preference criteria are satisfied
        return true; // Replace with actual logic
    }
}
