import streamlit as st
import requests
import pandas as pd

# API Base URL
BASE_URL = "http://localhost:8080"

# Helper functions for API requests
def get(endpoint):
    return requests.get(f"{BASE_URL}{endpoint}")

def post(endpoint, payload):
    return requests.post(f"{BASE_URL}{endpoint}", json=payload)

# Streamlit App
st.set_page_config(page_title="Automated Timetable Scheduler", layout="wide")

# Sidebar Navigation
st.sidebar.title("Navigation")
page = st.sidebar.radio("Go to", ["Timetable Management", "Subject Management", "Faculty Preferences"])

# Timetable Management
if page == "Timetable Management":
    st.title("Timetable Management")

    # Generate Timetable
    st.header("Generate Timetable")
    
    # 1. Get basic inputs
    department = st.text_input("Department")
    semester = st.text_input("Semester")
    
    # 2. Fetch and select subjects
    subjects_response = get("/api/subjects")
    if subjects_response.status_code == 200:
        subjects = subjects_response.json()
        selected_subjects = st.multiselect(
            "Select Subjects",
            options=[f"{subject['name']} ({subject['code']})" for subject in subjects],
            format_func=lambda x: x.split(" (")[0]
        )
        
        # 3. Additional inputs
        max_sessions_per_day = st.number_input("Max Sessions Per Day", min_value=1, max_value=10, value=2)
        desired_free_periods = st.number_input("Desired Free Periods", min_value=0, max_value=20, value=9)
        
        # 4. Generate button
        if st.button("Generate Timetable"):
            if not selected_subjects:
                st.error("Please select at least one subject.")
            elif not department or not semester:
                st.error("Department and Semester are required.")
            else:
                # Map selected subjects to their full details
                selected_subjects_details = [
                    subject for subject in subjects 
                    if f"{subject['name']} ({subject['code']})" in selected_subjects
                ]
                
                # Prepare the payload
                payload = {
                    "department": department,
                    "semester": semester,
                    "subjects": selected_subjects_details,
                    "maxSessionsPerDay": max_sessions_per_day,
                    "desiredFreePeriods": desired_free_periods
                }
                
                # Send request
                response = post("/api/timetable/generate", payload)
                st.write("Payload:", payload)  # Debug payload
                
                if response.status_code == 200:
                    st.success("Timetable generated successfully!")
                else:
                    st.error(f"Failed to generate timetable: {response.text}")
    else:
        st.error("Failed to fetch subjects. Please ensure the backend is running.")

    # Validate Timetable
    st.header("Validate Timetable")
    if st.button("Validate Timetable"):
        response = post("/api/timetable/validate", {})
        
        if response.status_code == 200:
            result = response.json()
            if result["status"] == "valid":
                st.success(result["message"])
            else:
                st.error(result["message"])
                if "violations" in result:
                    st.write("Violations found:")
                    for violation in result["violations"]:
                        st.write(f"- {violation}")
        else:
            st.error(f"Failed to validate timetable: {response.text}")

    # Download Timetable
    st.header("Download Timetable")
    format = st.selectbox("Select Format", ["CSV", "Excel"])
    if st.button("Download Timetable"):
        endpoint = f"/api/timetable/download/{format.lower()}"
        response = get(endpoint)
        if response.status_code == 200:
            st.download_button("Download", response.content, f"timetable.{format.lower()}")
        else:
            st.error("Failed to download timetable.")

# Subject Management
elif page == "Subject Management":
    st.title("Subject Management")

    # View Subjects
    st.header("View Subjects")
    response = get("/api/subjects")
    if response.status_code == 200:
        subjects = response.json()
        st.table(subjects)
    else:
        st.error("Failed to fetch subjects.")

    # Add Subject
    st.header("Add Subject")
    name = st.text_input("Name")
    code = st.text_input("Code")
    faculty = st.text_input("Faculty")
    alternate_faculty = st.text_input("Alternate Faculty")  # New field for alternate faculty
    hours_per_week = st.number_input("Hours per Week", min_value=1, max_value=40)
    lab_required = st.checkbox("Lab Required")
    department = st.text_input("Department")
    available = st.checkbox("Available", value=True)

    if st.button("Add Subject"):
        payload = {
            "name": name,
            "code": code,
            "faculty": faculty,
            "alternateFaculty": alternate_faculty,  # Include alternate faculty in the payload
            "hoursPerWeek": hours_per_week,
            "labRequired": lab_required,
            "department": department,
            "available": available
        }
        response = post("/api/subjects", payload)
        if response.status_code == 200:
            st.success("Subject added successfully!")
        else:
            st.error(f"Failed to add subject: {response.text}")

# Faculty Preferences
elif page == "Faculty Preferences":
    st.title("Faculty Preferences")

    # Add Faculty Preference
    st.header("Add Faculty Preference")
    
    # Get list of faculty from subjects
    subjects_response = get("/api/subjects")
    faculty_list = []
    if subjects_response.status_code == 200:
        subjects = subjects_response.json()
        faculty_list = sorted(list(set(s['faculty'] for s in subjects)))
    
    # Input fields
    faculty = st.selectbox("Faculty Name", faculty_list)
    preferred_days = st.multiselect(
        "Preferred Days", 
        ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday"]
    )
    preferred_times = st.multiselect(
        "Preferred Times",
        ["8:45am - 9:30am", "9:30am - 10:15am", "10:15am - 11:00am", 
         "11:30am - 12:15pm", "12:15pm - 1:00pm"]
    )

    if st.button("Add Preference"):
        if not faculty:
            st.error("Please select a faculty member.")
        elif not preferred_days:
            st.error("Please select at least one preferred day.")
        else:
            payload = {
                "faculty": faculty,
                "preferredDays": preferred_days,
                "preferredTime": preferred_times
            }
            
            st.write("Sending payload:", payload)  # Debug payload
            
            response = post("/api/faculty/preferences", payload)
            
            if response.status_code == 200:
                st.success("Faculty preference added successfully!")
            else:
                st.error(f"Failed to add preference: {response.text}")