import streamlit as st
import requests
import pandas as pd
import hashlib

# Initialize session state for login
if 'logged_in' not in st.session_state:
    st.session_state.logged_in = False

def check_password(username, password):
    # For demo purposes - replace with secure authentication
    CORRECT_USERNAME = "admin"
    CORRECT_PASSWORD = "timetable123"  # In production, use hashed passwords
    return username == CORRECT_USERNAME and password == CORRECT_PASSWORD

def login_page():
    st.markdown("""
        <div style='text-align: center'>
            <h1>🎓 Automated Timetable Manager</h1>
            <h3>Login</h3>
        </div>
    """, unsafe_allow_html=True)
    
    username = st.text_input("Username")
    password = st.text_input("Password", type="password")
    
    if st.button("Login"):
        if check_password(username, password):
            st.session_state.logged_in = True
            st.success("Login successful!")
            st.rerun()
        else:
            st.error("Invalid username or password")

def main_app():
    # Your existing application code
    # API Base URL
    BASE_URL = "http://localhost:8080"
    
    # Helper functions for API requests
    def get(endpoint):
        return requests.get(f"{BASE_URL}{endpoint}")

    def post(endpoint, payload):
        return requests.post(f"{BASE_URL}{endpoint}", json=payload)

    # Create a function for notifications
    def show_notification(type, message):
        if type == "success":
            st.balloons()  # Show celebration animation
        st.toast(message)  # Show toast notification

    # Custom sidebar header with emoji
    st.sidebar.markdown("# 📚 Navigation")

    # Sidebar Navigation
    page = st.sidebar.radio("Go to", ["Timetable Management", "Subject Management", "Faculty Preferences"])

    # Add to sidebar
    with st.sidebar:
        st.markdown("---")
        with st.expander("Help & Documentation"):
            st.markdown("""
            ### Quick Guide
            1. **Generate Timetable**: Select subjects and set parameters
            2. **View Subjects**: Manage your subject database
            3. **Faculty Preferences**: Set teaching preferences
            
            ### Tips
            - Use filters to find subjects quickly
            - Download timetable in CSV for Excel editing
            - Set faculty preferences before generating timetable
            """)

    # Add to sidebar
    with st.sidebar:
        st.markdown("---")
        st.header("Feedback")
        feedback = st.text_area("Share your feedback")
        if st.button("Submit Feedback"):
            # Store feedback
            st.success("Thank you for your feedback!")

    # Timetable Management
    if page == "Timetable Management":
        st.title("Timetable Management")
        
        # Dashboard metrics
        col1, col2, col3 = st.columns(3)
        with col1:
            total_subjects = len(get("/api/subjects").json())
            st.metric("Total Subjects", total_subjects)
        with col2:
            total_faculty = len(set(s['faculty'] for s in get("/api/subjects").json()))
            st.metric("Total Faculty", total_faculty)
        with col3:
            total_preferences = len(get("/api/faculty/preferences").json())
            st.metric("Faculty Preferences Set", total_preferences)
        
        # Get inputs
        department = st.text_input("Department")
        semester = st.text_input("Semester")
        
        # Fetch and select subjects
        subjects_response = get("/api/subjects")
        if subjects_response.status_code == 200:
            subjects = subjects_response.json()
            selected_subjects = st.multiselect(
                "Select Subjects",
                options=[f"{subject['name']} ({subject['code']})" for subject in subjects],
                format_func=lambda x: x.split(" (")[0]
            )
            
            # Additional inputs
            max_sessions_per_day = st.number_input("Max Sessions Per Day", min_value=1, max_value=10, value=2)
            desired_free_periods = st.number_input("Desired Free Periods", min_value=0, max_value=20, value=9)
            
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
                        show_notification("success", "Timetable generated successfully!")
                    else:
                        st.error(f"Failed to generate timetable: {response.text}")
        else:
            st.error("Failed to fetch subjects. Please ensure the backend is running.")

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
            search = st.text_input("Search Subjects")
            filter_dept = st.multiselect("Filter by Department", list(set(s['department'] for s in subjects)))

            filtered_subjects = [s for s in subjects 
                                if (search.lower() in s['name'].lower() or search.lower() in s['code'].lower())
                                and (not filter_dept or s['department'] in filter_dept)]
            if filtered_subjects:
                df = pd.DataFrame(filtered_subjects)
                # Reorder and select only the most important columns
                columns = ['name', 'code', 'faculty', 'department', 'hoursPerWeek', 'labRequired']
                df = df[columns]
                # Rename columns for better display
                df.columns = ['Name', 'Code', 'Faculty', 'Department', 'Hours/Week', 'Lab']
                # Display the table with custom height
                st.dataframe(df, height=300)
            else:
                st.info("No subjects found matching the criteria.")
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

# Main flow control
if not st.session_state.logged_in:
    login_page()
else:
    main_app()

# Add logout button in sidebar when logged in
if st.session_state.logged_in:
    with st.sidebar:
        if st.button("Logout"):
            st.session_state.logged_in = False
            st.rerun()  # Changed from experimental_rerun() to rerun()