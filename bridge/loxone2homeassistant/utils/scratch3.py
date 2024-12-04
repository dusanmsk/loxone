import streamlit as st
import pandas as pd

df = pd.DataFrame(
    [
        {"command": "st.selectbox", "rating": 4},
        { "command": "st.balloons", "rating": 5},
        { "command": "st.time_input", "rating": 3},
    ]
)

#selected_rows = st.multiselect("Select rows", df.index, format_func=lambda x: df.loc[x, "command"])
#st.dataframe(df, use_container_width=True)
selected_rows = st.data_editor(df, use_container_width=True)


if st.button("Submit"):
    st.write(f"Selected rows: {str(selected_rows)})")
