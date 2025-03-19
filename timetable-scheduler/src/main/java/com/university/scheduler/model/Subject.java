package com.university.scheduler.model;

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

    @Column(nullable = false)
    private String faculty;

    @Column
    private int hoursPerWeek;

    @Column
    private boolean labRequired;

    @Column
    private String department;

    public Subject() {
    }

    public Subject(String name, String code, String faculty, int hoursPerWeek, boolean labRequired, String department) {
        this.name = name;
        this.code = code;
        this.faculty = faculty;
        this.hoursPerWeek = hoursPerWeek;
        this.labRequired = labRequired;
        this.department = department;
    }

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

    public void setId(Long id) {
        this.id = id;
    }

    public void setName(String name) {
        this.name = name;
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

    @Override
    public String toString() {
        return "Subject{" +
                "id=" + id +
                ", name='" + name + '\'' +
                ", code='" + code + '\'' +
                ", faculty='" + faculty + '\'' +
                ", hoursPerWeek=" + hoursPerWeek +
                ", labRequired=" + labRequired +
                ", department='" + department + '\'' +
                '}';
    }
}
