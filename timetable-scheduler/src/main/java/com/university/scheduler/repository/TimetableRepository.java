package com.university.scheduler.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.university.scheduler.model.TimetableEntry;

@Repository
public interface TimetableRepository extends JpaRepository<TimetableEntry, Long> {
}
