from flask import Flask, render_template, request

app = Flask(__name__)

# Example list of strings to display in the dropdown
options = ["Option 1", "Option 2", "Option 3", "Option 4", "Lama 1", "Lama 2", "Koza 1", "Koza 2"]
filtered_options = options

def foo(selected_item):
    print(f"foo() called with: {selected_item}")

@app.route("/", methods=["GET", "POST"])
def index():
    selected_option = None
    global filtered_options
    if request.method == "POST":
        if "filter" in request.form:
            filter_text = request.form.get("filter_text", "").lower()
            filtered_options = [option for option in options if filter_text in option.lower()]
        else:
            selected_option = request.form.getlist("dropdown")
            foo(selected_option)
    return render_template("index.html", options=filtered_options, selected_option=selected_option)

if __name__ == "__main__":
    app.run(debug=True)
