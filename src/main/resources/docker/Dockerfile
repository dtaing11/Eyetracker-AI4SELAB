FROM python:3.10-slim

# Install system dependencies required by tobii_research
RUN apt-get update && apt-get install -y \
    libavahi-client3 \
    libavahi-common3 \
    libavahi-common-data \
 && rm -rf /var/lib/apt/lists/*

# Python deps
COPY requirements.txt /app/requirements.txt
WORKDIR /app
RUN pip install --no-cache-dir -r requirements.txt

# Copy your script
COPY eyetracker.py /app/eyetracker.py

# Run unbuffered so logs flush immediately
CMD ["python", "-u", "eyetracker.py"]
