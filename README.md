# Automated Timetable Scheduler

A comprehensive system for generating and managing academic timetables with intelligent scheduling algorithms, constraint handling, and faculty preference management.

[![GitHub repo](https://img.shields.io/badge/GitHub-Repository-green.svg)](https://github.com/Achlys2004/Automated-Time-Table-Scheduler)
[![Java Version](https://img.shields.io/badge/Java-17-orange.svg)](https://www.oracle.com/java/technologies/javase/jdk17-archive-downloads.html)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

## Table of Contents

- [Overview](#overview)
- [Key Features](#key-features)
- [System Architecture](#system-architecture)
- [Installation & Setup](#installation--setup)
- [Basic Usage](#basic-usage)
- [Project Structure](#project-structure)
- [API Reference](#api-reference)
- [License](#license)
- [Detailed Documentation](#detailed-documentation)

## Overview

The Automated Timetable Scheduler is a sophisticated solution for the complex problem of academic scheduling. It generates optimized timetables while respecting various constraints such as faculty preferences, lab requirements, consecutive session limits, and proper break periods. The system provides RESTful APIs for timetable generation, validation, and management of subjects and faculty preferences.

## Key Features

- **Intelligent Timetable Generation**: Weight-based algorithm for optimal scheduling
- **Constraint Management**:
  - Maximum consecutive sessions (2)
  - Maximum sessions per subject per day (2)
  - Lab session blocks (continuous 3-hour allocation)
  - Fixed break periods
  - Free period distribution (maximum 3 per day)
- **Faculty Preference Support**: Prioritize faculty's preferred teaching days
- **Lab Session Handling**: Special management for lab subjects
- **Export Options**: Download timetables as CSV or Excel files
- **Teacher Availability Management**: Update teacher availability and adjust timetables accordingly
- **Subject CRUD Operations**: Complete management of subject data
- **Timetable Validation**: Comprehensive validation against all constraints
- **Automatic Constraint Violation Fixing**: Ability to fix invalid timetables

## System Architecture

The system follows a standard Spring Boot architecture with:

1. **Controller Layer**: REST API endpoints for timetable and subject operations
2. **Service Layer**: Business logic including the scheduling algorithm
3. **Repository Layer**: Data access interfaces for JPA/Hibernate
4. **Model Layer**: JPA entity classes and request/response models

## Installation & Setup

### Prerequisites

- JDK 17 or higher
- Maven 3.6+ or Gradle 7.0+
- (Optional) MySQL for production deployment

### Installation Steps

1. **Clone Repository**

```bash
git clone https://github.com/Achlys2004/Automated-Time-Table-Scheduler.git
cd Automated-Time-Table-Scheduler
```

2. **Build Application**

```bash
cd timetable-scheduler
./mvnw clean install
```

3. **Configure Database**

Edit `src/main/resources/application.properties`:

```properties
# Development with H2 (in-memory database)
spring.datasource.url=jdbc:h2:mem:timetabledb
spring.datasource.driverClassName=org.h2.Driver
spring.datasource.username=sa
spring.datasource.password=password
spring.jpa.database-platform=org.hibernate.dialect.H2Dialect
spring.h2.console.enabled=true

# For production with MySQL (uncomment)
# spring.datasource.url=jdbc:mysql://localhost:3306/timetabledb
# spring.datasource.username=root
# spring.datasource.password=password
# spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MySQL8Dialect
```

4. **Run Application**

```bash
./mvnw spring-boot:run
```

5. **Access API**

- Base URL: `http://localhost:8080/api/`

## Basic Usage

### Generating Your First Timetable

1. Create subjects using the subject management API
2. Generate a timetable by sending a POST request to `/api/timetable/generate`
3. Retrieve or download the generated timetable

### Advanced Configuration Options

- Faculty preferences
- Free period distribution
- Teacher availability management
- Timetable validation

For detailed usage instructions including test data and examples, see our [Advanced Usage Guide](necessary_details.md#test-scenarios).

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

## API Reference

This section provides a high-level overview of available endpoints. For detailed request/response examples and testing information, see our [API Documentation](necessary_details.md#api-response-examples).

### Timetable Management Endpoints

- `POST /api/timetable/generate`: Generate a new timetable
- `POST /api/timetable/validate`: Validate a timetable against constraints
- `GET /api/timetable`: Retrieve all timetable entries
- `GET /api/timetable/download/csv`: Export timetable as CSV
- `GET /api/timetable/download/excel`: Export timetable as Excel
- `POST /api/timetable/updateTeacher`: Update teacher availability

### Subject Management Endpoints

- `GET /api/subjects`: Retrieve all subjects
- `GET /api/subjects/{id}`: Retrieve a subject by ID
- `GET /api/subjects/department/{department}`: Retrieve subjects by department
- `POST /api/subjects`: Create a new subject
- `PUT /api/subjects/{id}`: Update an existing subject
- `DELETE /api/subjects/{id}`: Delete a subject

## License

This project is licensed under the MIT License - see the LICENSE file for details.

## Detailed Documentation

For comprehensive technical details, including:

- Frontend implementation guidelines
- Test data and scenarios
- Example JSON payloads
- Detailed API responses
- Performance considerations

See our [Detailed Technical Documentation](necessary_details.md).
