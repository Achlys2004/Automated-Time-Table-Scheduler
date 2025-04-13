package com.university.scheduler.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import com.university.scheduler.model.FacultyPreference;
import java.util.List;
import java.util.Optional;

@Repository
public interface FacultyPreferenceRepository extends JpaRepository<FacultyPreference, Long> {
    
    // Find preference by faculty name
    Optional<FacultyPreference> findByFaculty(String faculty);
    
    // Find all preferences for a list of faculty members
    List<FacultyPreference> findByFacultyIn(List<String> faculties);
    
    // Check if preference exists for a faculty
    boolean existsByFaculty(String faculty);
    
    // Delete preference by faculty name
    void deleteByFaculty(String faculty);
}
