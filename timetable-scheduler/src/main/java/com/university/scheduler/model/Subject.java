package com.university.scheduler.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;

@Entity
@Table(name = "subjects")
public class Subject {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String code;

    // Use @JsonProperty to map "faculty" from JSON (if needed)
    @Column(nullable = false)
    @JsonProperty("faculty")
    private String faculty;

    @Column
    private int hoursPerWeek;

    @Column
    private boolean labRequired;

    @Column
    private String department;

    @Column
    private boolean available = true;

    @Column
    private String alternateFaculty;

    public Subject() {
    }

    public Subject(String name, String code, String faculty, int hoursPerWeek, boolean labRequired, String department) {
        this.name = name;
        this.code = code;
        this.faculty = faculty;
        this.hoursPerWeek = hoursPerWeek;
        this.labRequired = labRequired;
        this.department = department;
        this.available = true;
        this.alternateFaculty = null;
    }

    // Getters
    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getCode() {
        return code;
    }

    public String getFaculty() {
        return faculty;
    }

    public int getHoursPerWeek() {
        return hoursPerWeek;
    }

    public boolean isLabRequired() {
        return labRequired;
    }

    public String getDepartment() {
        return department;
    }

    public boolean isAvailable() {
        return available;
    }

    public String getAlternateFaculty() {
        return alternateFaculty;
    }

    // Setters
    public void setId(Long id) {
        this.id = id;
    }

    public void setName(String name) {
        this.name = name;
        // Auto-generate code if it is null or empty.
        if (this.code == null || this.code.trim().isEmpty()) {
            this.code = name.replaceAll("\\s+", "").toUpperCase();
        }
    }

    public void setCode(String code) {
        this.code = code;
    }

    public void setFaculty(String faculty) {
        this.faculty = faculty;
    }

    public void setHoursPerWeek(int hoursPerWeek) {
        this.hoursPerWeek = hoursPerWeek;
    }

    public void setLabRequired(boolean labRequired) {
        this.labRequired = labRequired;
    }

    public void setDepartment(String department) {
        this.department = department;
    }

    public void setAvailable(boolean available) {
        this.available = available;
    }

    public void setAlternateFaculty(String alternateFaculty) {
        this.alternateFaculty = alternateFaculty;
    }
}
