# Automated Timetable Scheduler - Technical Documentation

This document provides detailed technical information for developers implementing and testing the Automated Timetable Scheduler, with particular focus on frontend integration, test data, and advanced usage scenarios.

## Table of Contents

- [API Endpoints](#api-endpoints)
- [Data Models](#data-models)
- [Scheduling Algorithm](#scheduling-algorithm)
- [Implementation Details](#implementation-details)
- [Test Data & Scenarios](#test-data--scenarios)
  - [Example JSON Payloads](#example-json-payloads)
  - [Test Scenarios](#test-scenarios)
  - [API Response Examples](#api-response-examples)
- [Frontend Implementation Guide](#frontend-implementation-guide)
  - [API Integration Guidelines](#api-integration-guidelines)
  - [Time Slots & Break Structure](#time-slots--break-structure)
  - [UI Component Guidelines](#ui-component-guidelines)
  - [State Management Recommendations](#state-management-recommendations)
  - [Performance Considerations](#performance-considerations)
  - [Accessibility Features](#accessibility-features)
- [Advanced Usage Guide](#advanced-usage-guide)

## API Endpoints

### Timetable Management

#### Generate Timetable

- **URL:** `/api/timetable/generate`
- **Method:** POST
- **Request Body:** [TimetableRequest](#timetablerequest)
- **Response:** String message confirming generation
- **Description:** Generates a timetable using the weighted algorithm based on the provided request parameters
- **Implementation:** Uses weighted randomization with constraint handling to schedule subjects
- **Error Responses:**
  - 400 Bad Request: If subjects list is empty or null

#### Validate Timetable

- **URL:** `/api/timetable/validate`
- **Method:** POST
- **Request Body:** [TimetableRequest](#timetablerequest)
- **Response:**
  - If valid: `{"status": "valid", "message": "Timetable is valid and meets all requirements"}`
  - If invalid: `{"status": "invalid", "violations": [...], "fixedTimetable": {...}}`
- **Description:** Validates the current timetable against scheduling constraints and returns any violations
- **Implementation:** Checks for constraint violations and provides auto-fix suggestions
- **Error Responses:**
  - 400 Bad Request: If subjects list is empty or null

#### Get All Timetable Entries

- **URL:** `/api/timetable`
- **Method:** GET
- **Response:** Array of [TimetableEntry](#timetableentry) objects
- **Description:** Retrieves all timetable entries currently stored in the database

#### Download Timetable as CSV

- **URL:** `/api/timetable/download/csv`
- **Method:** GET
- **Response:** CSV file download
- **Description:** Exports the current timetable in CSV format with days as rows and time slots as columns
- **Implementation:** Writes CSV with comma-separated values for all timetable entries

#### Download Timetable as Excel

- **URL:** `/api/timetable/download/excel`
- **Method:** GET
- **Response:** Excel file download
- **Description:** Exports the current timetable in Excel format with days as rows and time slots as columns
- **Implementation:** Uses Apache POI to generate Excel workbook with auto-sized columns

#### Update Teacher Availability

- **URL:** `/api/timetable/updateTeacher`
- **Method:** POST
- **Parameters:**
  - `teacher` (string, **required**): The name of the teacher to update
  - `available` (boolean, **required**): Whether the teacher is available
  - `newTeacher` (string, optional): If teacher is unavailable, the replacement teacher
  - `updateOldTimetable` (boolean, **required**): Whether to update the existing timetable or generate a new one
- **Response:** String message confirming update
- **Description:** Updates teacher availability and optionally reassigns classes
- **Implementation:** Updates teacher assignments in timetable entries and subject repository

### Subject Management

#### Get All Subjects

- **URL:** `/api/subjects`
- **Method:** GET
- **Response:** Array of [Subject](#subject) objects
- **Description:** Retrieves all subjects in the database

#### Get Subject by ID

- **URL:** `/api/subjects/{id}`
- **Method:** GET
- **Path Variable:** `id` (long, **required**)
- **Response:** [Subject](#subject) object
- **Description:** Retrieves a specific subject by its ID
- **Error Responses:**
  - 404 Not Found: If subject with given ID doesn't exist

#### Get Subjects by Department

- **URL:** `/api/subjects/department/{department}`
- **Method:** GET
- **Path Variable:** `department` (string, **required**)
- **Response:** Array of [Subject](#subject) objects
- **Description:** Retrieves all subjects for a specific department

#### Create Subject

- **URL:** `/api/subjects`
- **Method:** POST
- **Request Body:** [Subject](#subject) object
- **Response:** Created [Subject](#subject) object with ID
- **Description:** Creates a new subject in the database

#### Update Subject

- **URL:** `/api/subjects/{id}`
- **Method:** PUT
- **Path Variable:** `id` (long, **required**)
- **Request Body:** [Subject](#subject) object
- **Response:** Updated [Subject](#subject) object
- **Description:** Updates an existing subject
- **Error Responses:**
  - 404 Not Found: If subject with given ID doesn't exist

#### Delete Subject

- **URL:** `/api/subjects/{id}`
- **Method:** DELETE
- **Path Variable:** `id` (long, **required**)
- **Response:** 200 OK status code if successful
- **Description:** Deletes a subject from the database
- **Error Responses:**
  - 404 Not Found: If subject with given ID doesn't exist

## Data Models

### Subject

Represents a course that needs to be scheduled in the timetable.

```json
{
  "id": long,
  "name": string,                // Required: Name of the subject
  "code": string,                // Required: Unique code for the subject
  "faculty": string,             // Required: Name of the teacher
  "hoursPerWeek": int,           // Required: Number of hours to be scheduled per week
  "labRequired": boolean,        // Required: Whether the subject requires lab sessions
  "department": string,          // Required: Department offering the subject
  "available": boolean,          // Optional: Whether the subject is available for scheduling (defaults to true)
  "alternateFaculty": string     // Optional: Alternative faculty member
}
```

### TimetableEntry

Represents a single cell in the timetable.

```json
{
  "id": long,
  "day": string,        // Required: Day of the week (Monday, Tuesday, etc.)
  "sessionNumber": int, // Required: Session number (1-11, corresponding to time slot)
  "subject": string     // Required: Subject name or special values like "Free Period"
}
```

### TimetableRequest

Contains all parameters needed to generate a timetable.

```json
{
  "department": string,                      // Required: Department for which timetable is generated
  "semester": string,                        // Required: Semester for which timetable is generated
  "subjects": [Subject],                     // Required: List of subjects to schedule (if not provided, taken from database)
  "facultyPreferences": [FacultyPreference], // Optional: Teacher preferences for scheduling
  "availableTimeSlots": [string],            // Optional: Available time slots (uses default if not provided)
  "breakTimes": [string],                    // Optional: Break times (uses default if not provided)
  "maxSessionsPerDay": int,                  // Optional: Maximum sessions per day per subject (defaults to 2)
  "desiredFreePeriods": int                  // Optional: Desired number of free periods in the timetable (defaults to 9)
}
```

### FacultyPreference

Represents a teacher's preferences for scheduling.

```json
{
  "faculty": string,         // Required: Name of the teacher
  "preferredDays": [string], // Optional: List of preferred days
  "preferredTime": [string]  // Optional: List of preferred times (not fully implemented in current version)
}
```

## Scheduling Algorithm

### Overview

The timetable generation algorithm follows a multi-phase approach to schedule subjects efficiently while respecting constraints.

## Implementation Details

### Subject Representation

Subjects appear in the timetable with the following format:

- Regular classes: `<faculty> - <subject name>`
- Lab sessions: `<faculty> - <subject name> Lab`

### Free Period Distribution

The scheduler implements a sophisticated free period distribution strategy.

### Lab Block Placement

Lab blocks are always 3 consecutive sessions.

### Teacher Availability Updates

When a teacher is marked as unavailable, the system provides options to update the timetable.

### Validation & Auto-fixing

The system provides comprehensive validation and auto-fixing.

## Test Data & Scenarios

This section provides test data and scenarios for validating the timetable scheduler implementation.

## Example JSON Payloads

### Minimal Required JSON Example

This example shows the minimum required fields to generate a timetable:

```json
{
  "department": "Computer Science",
  "semester": "6",
  "subjects": [
    {
      "name": "Database Systems",
      "code": "CS601",
      "faculty": "Dr. Smith",
      "hoursPerWeek": 6,
      "labRequired": true,
      "department": "Computer Science"
    },
    {
      "name": "Computer Networks",
      "code": "CS602",
      "faculty": "Prof. Johnson",
      "hoursPerWeek": 6,
      "labRequired": true,
      "department": "Computer Science"
    },
    {
      "name": "Software Engineering",
      "code": "CS603",
      "faculty": "Dr. Williams",
      "hoursPerWeek": 6,
      "labRequired": false,
      "department": "Computer Science"
    }
  ],
  "maxSessionsPerDay": 2,
  "desiredFreePeriods": 9
}
```

### Complete JSON Example

This example includes all possible fields that can be sent to the backend:

```json
{
  "department": "Computer Science",
  "semester": "6",
  "subjects": [
    {
      "name": "Database Systems",
      "code": "CS601",
      "faculty": "Dr. Smith",
      "hoursPerWeek": 6,
      "labRequired": true,
      "department": "Computer Science",
      "available": true,
      "alternateFaculty": "Dr. Davis"
    },
    {
      "name": "Computer Networks",
      "code": "CS602",
      "faculty": "Prof. Johnson",
      "hoursPerWeek": 6,
      "labRequired": true,
      "department": "Computer Science",
      "available": true,
      "alternateFaculty": null
    },
    {
      "name": "Software Engineering",
      "code": "CS603",
      "faculty": "Dr. Williams",
      "hoursPerWeek": 6,
      "labRequired": false,
      "department": "Computer Science",
      "available": true,
      "alternateFaculty": null
    },
    {
      "name": "Operating Systems",
      "code": "CS604",
      "faculty": "Prof. Brown",
      "hoursPerWeek": 6,
      "labRequired": false,
      "department": "Computer Science",
      "available": true,
      "alternateFaculty": "Prof. Turner"
    },
    {
      "name": "Machine Learning",
      "code": "CS606",
      "faculty": "Dr. Miller",
      "hoursPerWeek": 6,
      "labRequired": false,
      "department": "Computer Science",
      "available": true,
      "alternateFaculty": null
    }
  ],
  "facultyPreferences": [
    {
      "faculty": "Dr. Smith",
      "preferredDays": ["Monday", "Wednesday", "Friday"],
      "preferredTime": ["8:45am - 9:30am", "9:30am - 10:15am"]
    },
    {
      "faculty": "Prof. Johnson",
      "preferredDays": ["Tuesday", "Thursday"],
      "preferredTime": ["11:30am - 12:15pm", "12:15pm - 1:00pm"]
    }
  ],
  "availableTimeSlots": [
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
  ],
  "breakTimes": ["11:00am - 11:30am", "1:45pm - 2:30pm"],
  "maxSessionsPerDay": 2,
  "desiredFreePeriods": 9
}
```

### Validation Response Example

Example response from the `/api/timetable/validate` endpoint when violations are found:

```json
{
  "status": "invalid",
  "violations": [
    "Total free periods is 12, should be 9",
    "Day Monday has 4 free periods, exceeding maximum of 3",
    "Subject Dr. Smith - Database Systems has more than 2 consecutive sessions on Wednesday",
    "Subject Dr. Miller - Machine Learning has 3 sessions on Tuesday, exceeding maximum of 2"
  ],
  "fixedTimetable": {
    "Monday": [
      "Dr. Smith - Database Systems",
      "Dr. Smith - Database Systems",
      "Free Period",
      "Short Break (11:00-11:30)",
      "Dr. Williams - Software Engineering",
      "Free Period",
      "Dr. Miller - Machine Learning",
      "Long Break (1:45-2:30)",
      "Free Period",
      "Dr. Williams - Software Engineering",
      "Dr. Miller - Machine Learning"
    ],
    "Tuesday": [
      "Dr. Johnson - Computer Networks",
      "Dr. Johnson - Computer Networks",
      "Prof. Brown - Operating Systems",
      "Short Break (11:00-11:30)",
      "Dr. Smith - Database Systems",
      "Dr. Miller - Machine Learning",
      "Prof. Brown - Operating Systems",
      "Long Break (1:45-2:30)",
      "Dr. Williams - Software Engineering",
      "Free Period",
      "Dr. Smith - Database Systems"
    ],
    "Wednesday": [
      "Prof. Johnson - Computer Networks Lab",
      "Prof. Johnson - Computer Networks Lab",
      "Prof. Johnson - Computer Networks Lab",
      "Short Break (11:00-11:30)",
      "Dr. Williams - Software Engineering",
      "Dr. Williams - Software Engineering",
      "Dr. Miller - Machine Learning",
      "Long Break (1:45-2:30)",
      "Free Period",
      "Prof. Brown - Operating Systems",
      "Prof. Brown - Operating Systems"
    ],
    "Thursday": [
      "Dr. Smith - Database Systems Lab",
      "Dr. Smith - Database Systems Lab",
      "Dr. Smith - Database Systems Lab",
      "Short Break (11:00-11:30)",
      "Dr. Miller - Machine Learning",
      "Prof. Brown - Operating Systems",
      "Prof. Johnson - Computer Networks",
      "Long Break (1:45-2:30)",
      "Dr. Williams - Software Engineering",
      "Free Period",
      "Free Period"
    ],
    "Friday": [
      "Prof. Johnson - Computer Networks",
      "Dr. Miller - Machine Learning",
      "Dr. Smith - Database Systems",
      "Short Break (11:00-11:30)",
      "Prof. Brown - Operating Systems",
      "Dr. Williams - Software Engineering",
      "Prof. Johnson - Computer Networks",
      "Long Break (1:45-2:30)",
      "Free Period",
      "Prof. Brown - Operating Systems",
      "Dr. Miller - Machine Learning"
    ]
  }
}
```

Example response from the /api/timetable/validate endpoint when no violations are found:

```json
{
  "status": "valid",
  "message": "Timetable is valid and meets all requirements",
  ...
}
```

## Frontend Implementation Guide

### API Integration Guidelines

When integrating with the Timetable Scheduler API, follow these guidelines.

### Time Slots & Break Structure

The system uses the following fixed time slots.

### UI Component Guidelines

For timetable visualization in the frontend.

### State Management Recommendations

Use a state management solution to manage timetable data.

### Performance Considerations

Virtualize large timetable grids for better performance.

### Accessibility Features

Ensure proper ARIA labels for interactive elements.

## Advanced Usage Guide

This section provides advanced usage scenarios for the timetable scheduler.
