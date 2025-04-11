# Automated Timetable Scheduler API Documentation

## Table of Contents

- [API Endpoints](#api-endpoints)
- [Data Models](#data-models)
- [Scheduling Constraints](#scheduling-constraints)
- [Time Slots](#time-slots)
- [Special Constants](#special-constants)
- [Implementation Notes](#implementation-notes)
- [Example JSON Payloads](#example-json-payloads)

## API Endpoints

### Timetable Management

#### Generate Timetable (Simple Algorithm)

- **URL:** `/api/timetable/generate`
- **Method:** POST
- **Request Body:** [TimetableRequest](#timetablerequest)
- **Response:** String message confirming generation
- **Description:** Generates a timetable using the simple algorithm based on the provided request parameters

#### Generate Timetable (Backtracking Algorithm)

- **URL:** `/api/timetable/generate/backtracking`
- **Method:** POST
- **Request Body:** [TimetableRequest](#timetablerequest)
- **Response:** String message confirming generation
- **Description:** Generates a timetable using a more complex backtracking algorithm that tries to satisfy all constraints

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

#### Download Timetable as Excel

- **URL:** `/api/timetable/download/excel`
- **Method:** GET
- **Response:** Excel file download
- **Description:** Exports the current timetable in Excel format with days as rows and time slots as columns

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

#### Delete Subject

- **URL:** `/api/subjects/{id}`
- **Method:** DELETE
- **Path Variable:** `id` (long, **required**)
- **Response:** 200 OK status code if successful
- **Description:** Deletes a subject from the database

## Data Models

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

**Constraints:**

- `sessionNumber` must be between 1 and 11

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

**Notes:**

- If `labRequired` is true, the scheduler will try to allocate a continuous 3-hour block for the lab
- For lab subjects, the scheduler allocates hours as follows: 3 hours for lab, 3 hours for theory
- For theory-only subjects, all hours are allocated as theory sessions

### TimetableRequest

Contains all parameters needed to generate a timetable.

```json
{
  "department": string,                      // Required: Department for which timetable is generated
  "semester": string,                        // Required: Semester for which timetable is generated
  "subjects": [Subject],                     // Optional: List of subjects to schedule (if not provided, taken from database)
  "facultyPreferences": [FacultyPreference], // Optional: Teacher preferences for scheduling
  "availableTimeSlots": [string],            // Optional: Available time slots (uses default if not provided)
  "breakTimes": [string],                    // Optional: Break times (uses default if not provided)
  "maxSessionsPerDay": int,                  // Optional: Maximum sessions per day per subject (defaults to 2)
  "desiredFreePeriods": int                  // Optional: Desired number of free periods in the timetable
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

## Scheduling Constraints

The scheduler enforces the following constraints:

1. **Maximum Consecutive Sessions**: No more than 2 consecutive sessions of the same subject (except lab)

   - Constant: `MAX_CONSECUTIVE_SESSIONS = 2`

2. **Free Periods Per Day**: Maximum 3 free periods per day

   - Constant: `MAX_FREE_PERIODS_PER_DAY = 3`

3. **Sessions Per Subject Per Day**: Maximum 2 sessions of a subject per day

   - Constant: `MAX_SESSIONS_PER_DAY = 2`

4. **Lab Sessions**: Lab sessions are always scheduled as 3 consecutive slots on the same day

   - Lab sessions don't span across breaks

5. **Breaks**: Fixed break slots at specific times

   - Morning Break Index: 3 (11:00am - 11:30am)
   - Afternoon Break Index: 7 (1:45pm - 2:30pm)

6. **Faculty Preferences**:
   - Faculty with preferences get higher priority in scheduling
   - Preferred days get higher weight in scheduling

## Time Slots

The system uses the following fixed time slots:
1: "8:45am - 9:45am"
2: "9:45am - 10:15am"
3: "10:15am - 11:00am"
4: "11:00am - 11:30am" (Short Break)
5: "11:30am - 12:15pm"
6: "12:15pm - 1:00pm"
7: "1:00pm - 1:45pm"
8: "1:45pm - 2:30pm" (Long Break)
9: "2:30pm - 3:15pm"
10: "3:15pm - 4:00pm"
11: "4:00pm - 4:45pm"

## Special Constants

The scheduler uses the following special constants:

- `FREE_PERIOD = "Free Period"`: Indicates no class scheduled
- `SHORT_BREAK = "Short Break (11:00-11:30)"`: Morning break
- `LONG_BREAK = "Long Break (1:45-2:30)"`: Afternoon break
- `UNALLOCATED = "UNALLOCATED"`: Used during scheduling process, should not appear in final timetable

## Implementation Notes

### Timetable Generation Algorithms

The system provides two algorithms:

#### Simple Algorithm (/api/timetable/generate)

- Prioritizes subjects based on difficulty and lab requirements
- Places lab blocks first
- Distributes theory sessions with weighted randomization
- Enforces constraints on consecutive sessions and sessions per day
- Good for most use cases; faster but may not utilize slots optimally

#### Backtracking Algorithm (/api/timetable/generate/backtracking)

- Uses recursive backtracking to try all possible combinations
- More comprehensive constraint satisfaction
- Better at handling complex scheduling scenarios
- May take longer to execute for large datasets

### Subject Representation in Timetable

Subjects appear in the timetable with the following format:

- Regular classes: `<faculty> - <subject name>`
- Lab sessions: `<faculty> - <subject name> Lab`

### Free Period Distribution

The scheduler attempts to:

- Distribute free periods evenly across days
- Limit the number of free periods per day to `MAX_FREE_PERIODS_PER_DAY`
- Fill in extra free periods with subjects that need more hours when possible

### Lab Block Placement

Lab blocks are always 3 consecutive sessions and:

- Will not span across breaks
- Are placed before regular theory sessions
- Are limited to one lab block per day when possible

### Teacher Availability Updates

When a teacher is marked as unavailable:

- Their entries in the timetable can be either:
  - Replaced with a new teacher
  - Converted to free periods
- The teacher's subjects in the repository can be updated with a new faculty
- Optionally, the entire timetable can be regenerated

### Special Handling

The system handles special cases like:

- Constraint violations - automatic fixes for consecutive session violations
- Unallocated slots - conversion to free periods
- Ensuring all subjects have required hours - filling free periods if needed

## Example JSON Payloads

### Minimal Required JSON Example

This example shows the minimum required fields to generate a timetable:

```json
{
  "department": "Computer Science",
  "semester": "5",
  "subjects": [
    {
      "name": "Database Management Systems",
      "code": "CS301",
      "faculty": "Dr. Smith",
      "hoursPerWeek": 6,
      "labRequired": false,
      "department": "Computer Science"
    },
    {
      "name": "Web Programming",
      "code": "CS302",
      "faculty": "Prof. Johnson",
      "hoursPerWeek": 6,
      "labRequired": true,
      "department": "Computer Science"
    },
    {
      "name": "Computer Networks",
      "code": "CS303",
      "faculty": "Dr. Williams",
      "hoursPerWeek": 6,
      "labRequired": true,
      "department": "Computer Science"
    },
    {
      "name": "Discrete Mathematics",
      "code": "CS304",
      "faculty": "Prof. Brown",
      "hoursPerWeek": 6,
      "labRequired": false,
      "department": "Computer Science"
    }
  ]
}
```

### Complete JSON Example

This example includes all possible fields that can be sent to the backend:

```json
{
  "department": "Computer Science",
  "semester": "5",
  "subjects": [
    {
      "name": "Database Management Systems",
      "code": "CS301",
      "faculty": "Dr. Smith",
      "hoursPerWeek": 6,
      "labRequired": false,
      "department": "Computer Science",
      "available": true,
      "alternateFaculty": "Dr. Davis"
    },
    {
      "name": "Web Programming",
      "code": "CS302",
      "faculty": "Prof. Johnson",
      "hoursPerWeek": 6,
      "labRequired": true,
      "department": "Computer Science",
      "available": true,
      "alternateFaculty": "Prof. Miller"
    },
    {
      "name": "Computer Networks",
      "code": "CS303",
      "faculty": "Dr. Williams",
      "hoursPerWeek": 6,
      "labRequired": true,
      "department": "Computer Science",
      "available": true,
      "alternateFaculty": null
    },
    {
      "name": "Discrete Mathematics",
      "code": "CS304",
      "faculty": "Prof. Brown",
      "hoursPerWeek": 6,
      "labRequired": false,
      "department": "Computer Science",
      "available": true,
      "alternateFaculty": null
    },
    {
      "name": "Operating Systems",
      "code": "CS305",
      "faculty": "Dr. Wilson",
      "hoursPerWeek": 6,
      "labRequired": true,
      "department": "Computer Science",
      "available": false,
      "alternateFaculty": "Dr. Taylor"
    }
  ],
  "facultyPreferences": [
    {
      "faculty": "Dr. Smith",
      "preferredDays": ["Monday", "Wednesday", "Friday"],
      "preferredTime": ["8:45am - 9:45am", "9:45am - 10:15am"]
    },
    {
      "faculty": "Prof. Johnson",
      "preferredDays": ["Tuesday", "Thursday"],
      "preferredTime": ["11:30am - 12:15pm", "12:15pm - 1:00pm"]
    }
  ],
  "availableTimeSlots": [
    "8:45am - 9:45am",
    "9:45am - 10:15am",
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
  "desiredFreePeriods": 5
}
```
