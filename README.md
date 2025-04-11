# Automated Timetable Scheduler

An intelligent system for generating and managing academic timetables, designed to handle complex scheduling constraints and faculty preferences.

[![GitHub repo](https://img.shields.io/badge/GitHub-Repository-green.svg)](https://github.com/Achlys2004/Automated-Time-Table-Scheduler)

## 📋 Table of Contents

- [Features](#features)
- [Technologies Used](#technologies-used)
- [Installation](#installation)
- [Usage](#usage)
- [API Documentation](#api-documentation)
- [Project Structure](#project-structure)
- [Scheduling Algorithms](#scheduling-algorithms)
- [Contributing](#contributing)
- [License](#license)

## ✨ Features

- **Intelligent Scheduling**: Two different algorithms (simple and backtracking) for optimal timetable generation
- **Constraint Management**: Handles various scheduling constraints like:
  - Maximum consecutive sessions for a subject
  - Maximum sessions per subject per day
  - Lab session blocks
  - Break periods
- **Faculty Preferences**: Support for teacher day/time preferences
- **Export Options**: Download timetables as CSV or Excel files
- **Teacher Availability Management**: Update teacher availability and automatically adjust timetables
- **Subject Management**: Complete CRUD operations for subject management
- **Free Period Distribution**: Intelligent distribution of free periods

## 🛠️ Technologies Used

- **Backend**: Spring Boot, Java 17
- **Database**: JPA/Hibernate with H2/MySQL
- **API**: RESTful endpoints with JSON payloads
- **Export**: Apache POI for Excel generation, CSV support

## 🔧 Installation

1. Clone the repository:

```bash
git clone git@github.com:Achlys2004/Automated-Time-Table-Scheduler.git
cd Automated-Time-Table-Scheduler
```

2. Build the project using Maven:

```bash
cd timetable-scheduler
./mvnw clean install
```

3. Configure the database in `application.properties`:

```properties
# Use H2 for development
spring.datasource.url=jdbc:h2:mem:timetabledb
spring.datasource.driverClassName=org.h2.Driver
spring.datasource.username=sa
spring.datasource.password=password
spring.jpa.database-platform=org.hibernate.dialect.H2Dialect
spring.h2.console.enabled=true

# For production, use MySQL or another database
# spring.datasource.url=jdbc:mysql://localhost:3306/timetabledb
# spring.datasource.username=root
# spring.datasource.password=password
# spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MySQL8Dialect
```

## 🚀 Usage

1. Start the application:

```bash
./mvnw spring-boot:run
```

2. Access the API at `http://localhost:8080/api/`

3. Generate a timetable by sending a POST request to `/api/timetable/generate` with appropriate JSON payload (see [API Documentation](#api-documentation))

4. Download the generated timetable in CSV or Excel format

## 📚 API Documentation

For comprehensive API documentation, see [necessary_details.md](necessary_details.md) in this repository.

### Key Endpoints

#### Timetable Management

- `POST /api/timetable/generate` - Generate timetable (simple algorithm)
- `POST /api/timetable/generate/backtracking` - Generate timetable (backtracking algorithm)
- `GET /api/timetable` - Get all timetable entries
- `GET /api/timetable/download/csv` - Download timetable as CSV
- `GET /api/timetable/download/excel` - Download timetable as Excel

#### Subject Management

- `GET /api/subjects` - Get all subjects
- `POST /api/subjects` - Create new subject
- `PUT /api/subjects/{id}` - Update a subject
- `DELETE /api/subjects/{id}` - Delete a subject

### Example Request

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
    }
  ]
}
```

## 🗂️ Project Structure

timetable-scheduler/

├── src/main/java/com/university/scheduler/

│   ├── controller/

│   │   ├── SubjectController.java

│   │   └── TimetableController.java

│   ├── model/

│   │   ├── FacultyPreference.java

│   │   ├── Subject.java

│   │   ├── TimetableEntry.java

│   │   └── TimetableRequest.java

│   ├── repository/

│   │   ├── SubjectRepository.java

│   │   └── TimetableRepository.java

│   └── service/

│       └── TimetableService.java

└── src/main/resources/

    └── application.properties

## 🧮 Scheduling Algorithms

### Simple Algorithm

The simple algorithm prioritizes subjects based on lab requirements and difficulty, and uses a weighted randomization approach to distribute sessions across the week while ensuring constraints are met.

Key features:

- Places lab blocks first (3 consecutive hours) to ensure laboratory sessions have contiguous blocks
- Uses weighted randomization to distribute theory sessions across days
- Enforces constraints on consecutive sessions and sessions per day
- Distributes free periods evenly across the week
- Faster execution but may not utilize slots optimally in complex scenarios

Implementation highlights:

- Lab subjects are allocated with priority to secure 3-hour contiguous blocks
- Subject weights are calculated based on lab requirements and hours per week
- Faculty preferences are considered with priority weighting when provided

### Backtracking Algorithm

The backtracking algorithm uses a recursive approach to try all possible combinations, providing more comprehensive constraint satisfaction for complex scheduling scenarios.

Key features:

- Uses recursive backtracking to explore the solution space
- Enforces all constraints simultaneously
- Better at handling complex scheduling scenarios with tight constraints
- Prioritizes subjects based on difficulty of scheduling
- May take longer to execute for large datasets

Implementation highlights:

- Maintains running count of sessions per subject per day
- Checks for constraint violations before each placement
- Uses optimized ordering of subjects to place those with most constraints first
- Includes time limits to prevent excessive computation

## 👥 Contributing

Contributions are welcome! To contribute:

1. Fork the repository
2. Create a feature branch: `git checkout -b feature-name`
3. Commit your changes: `git commit -m 'Add some feature'`
4. Push to the branch: `git push origin feature-name`
5. Open a pull request

Please ensure your code follows the project's coding standards and include appropriate tests.

## 📄 License

This project is licensed under the MIT License - see the LICENSE file for details.

## 🙏 Acknowledgements

- This project was developed as part of the Object-Oriented Analysis and Design course
- Special thanks to all contributors and faculty advisors
