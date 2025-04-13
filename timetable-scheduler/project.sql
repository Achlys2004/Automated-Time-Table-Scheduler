use timetable_db;
INSERT INTO subjects (id, alternate_faculty, available, code, department, faculty, hours_per_week, lab_required, name)
VALUES
(1, 'Dr. Johnson', TRUE, 'CS601', 'Computer Science', 'Dr. Smith', 6, TRUE, 'Database Systems'),
(2, 'Dr. Brown', TRUE, 'CS602', 'Computer Science', 'Dr. Johnson', 4, FALSE, 'Computer Networks'),
(3, NULL, TRUE, 'CS603', 'Computer Science', 'Dr. Williams', 5, FALSE, 'Software Engineering'),
(4, 'Dr. Miller', FALSE, 'CS604', 'Computer Science', 'Dr. Brown', 3, TRUE, 'Operating Systems'),
(5, NULL, TRUE, 'CS605', 'Computer Science', 'Dr. Miller', 6, FALSE, 'Machine Learning');

INSERT INTO timetable_entry (id, day, session_number, subject)
VALUES
(1, 'Monday', 1, 'CS601'),
(2, 'Monday', 2, 'CS602'),
(3, 'Monday', 3, 'CS603'),
(4, 'Tuesday', 1, 'CS604'),
(5, 'Tuesday', 2, 'CS605'),
(6, 'Wednesday', 1, 'CS601'),
(7, 'Wednesday', 2, 'CS602'),
(8, 'Thursday', 1, 'CS603'),
(9, 'Thursday', 2, 'CS604'),
(10, 'Friday', 1, 'CS605');

select * from subjects;
 CREATE TABLE faculty_preferences (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    faculty VARCHAR(255) NOT NULL
);

CREATE TABLE faculty_preferred_days (
    faculty_preference_id BIGINT,
    preferred_days VARCHAR(255),
    FOREIGN KEY (faculty_preference_id) REFERENCES faculty_preferences(id)
);

CREATE TABLE faculty_preferred_times (
    faculty_preference_id BIGINT,
    preferred_time VARCHAR(255),
    FOREIGN KEY (faculty_preference_id) REFERENCES faculty_preferences(id)
);

SELECT * FROM subjects;
SELECT * FROM faculty_preferences;
SELECT * FROM faculty_preferred_days;

SELECT * FROM faculty_preferred_times;

SELECT * FROM timetable_entry;
ALTER TABLE timetable_entry 
ADD COLUMN is_lab_session BOOLEAN DEFAULT false;


ALTER TABLE timetable_entry 
ADD COLUMN is_lab_session BOOLEAN DEFAULT false;

DESCRIBE timetable_entry;

SELECT * from timetable_entry;

UPDATE timetable_entry 
SET is_lab_session = false 
WHERE is_lab_session IS NULL;

UPDATE timetable_entry 
SET is_lab_session = false 
WHERE id IN (SELECT id FROM (
    SELECT id FROM timetable_entry WHERE is_lab_session IS NULL
) AS temp);

SET SQL_SAFE_UPDATES = 0;