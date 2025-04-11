# Automated Time Table Scheduler - File Descriptions

## Project Structure

```
timetable-scheduler/
├── src/main/java/com/university/scheduler/
│   ├── controller/
│   │   ├── SubjectController.java     # Subject CRUD operations
│   │   └── TimetableController.java   # Timetable generation and export
│   ├── model/
│   │   ├── FacultyPreference.java     # Faculty preference data
│   │   ├── Subject.java               # Subject entity
│   │   ├── TimetableEntry.java        # Timetable entry entity
│   │   └── TimetableRequest.java      # Timetable generation request
│   ├── repository/
│   │   ├── SubjectRepository.java     # Subject data access
│   │   └── TimetableRepository.java   # Timetable data access
│   └── service/
│       └── TimetableService.java      # Core scheduling algorithm
└── src/main/resources/
    └── application.properties         # Application configuration
```

## Core Application Files

### Main Application
- **TimetableScheduleApplication.java**: The main entry point of the application that starts up the Spring Boot framework.
- **TimetableScheduleApplicationTests.java**: Simple test file to verify that the application loads correctly.

## Model Files (Data Objects)

- **Subject.java**: Represents a course or subject with properties like:
  - Name, code
  - Faculty teaching the subject
  - Number of hours per week
  - Whether it requires a lab
  - Department it belongs to

- **TimetableEntry.java**: Represents a single slot in the timetable with:
  - Day (Monday, Tuesday, etc.)
  - Session number (which period of the day)
  - Subject assigned to this slot

- **TimetableRequest.java**: Contains all the parameters needed to generate a timetable:
  - Department and semester
  - List of subjects to include
  - Faculty preferences for teaching times
  - Available time slots and break times
  - Number of desired free periods

- **FacultyPreference.java**: Stores information about when faculty members prefer to teach:
  - Faculty name
  - Preferred days of the week
  - Preferred time slots

## Repository Files (Database Access)

- **TimetableRepository.java**: Interface for database operations related to timetable entries.
- **SubjectRepository.java**: Interface for database operations related to subjects.

## Service Files (Business Logic)

- **TimetableService.java**: The core engine that handles all the logic for generating timetables:
  - Creates schedules following constraints like maximum consecutive sessions
  - Respects faculty preferences
  - Handles lab scheduling (which requires consecutive slots)
  - Validates timetables and fixes issues
  - Handles distribution of free periods

## Controller Files (API Endpoints)

- **TimetableController.java**: Handles HTTP requests related to timetable generation:
  - Endpoint to generate a new timetable
  - Endpoint to validate an existing timetable
  - Export options (download as CSV or Excel)
  - Teacher availability updates

- **SubjectController.java**: Handles HTTP requests related to subjects:
  - Create, read, update, and delete subjects
  - Get subjects by department

## Configuration Files

- **application.properties**: Contains configuration settings for:
  - SQL Database connection details (URL, username, password)
  - JPA and Hibernate settings
  - Other Spring Boot configurations

## Build and Runtime Files

- **pom.xml**: Maven project configuration file that lists:
  - Project dependencies
  - Build settings
  - Plugins

- **mvnw** and **mvnw.cmd**: Maven wrapper scripts that allow running Maven commands without installing Maven.

- **.mvn/wrapper/maven-wrapper.properties**: Configuration for the Maven wrapper.

## IDE and Version Control Files

- **.gitignore**: Lists files that Git should ignore when committing changes.
- **.gitattributes**: Configures how Git handles certain file types.
- **.vscode/settings.json**: Configuration settings for Visual Studio Code.
