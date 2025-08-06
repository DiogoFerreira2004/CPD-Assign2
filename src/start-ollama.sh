#!/bin/bash

# script to start ollama via docker
# this script manages the deployment of ollama, an ai model server, in a docker container
# it handles container creation, startup, and model availability checks

# check if docker is installed on the system before proceeding
echo "Checking if Docker is installed..."
if ! command -v docker &> /dev/null; then
    # if docker command is not found in the system path, show an error message
    echo "Docker not found. Please install Docker first."
    echo "Visit: https://docs.docker.com/get-docker/"
    exit 1
fi

# look for existing ollama container by filtering docker's container list
echo "Checking if the Ollama container already exists..."
# store the container id if found, will be empty string if not found
CONTAINER_ID=$(docker ps -a --filter "name=ollama" --format "{{.ID}}")

if [ -z "$CONTAINER_ID" ]; then
    # no container found, so we need to create a new one
    echo "Ollama container not found. Creating new container..."
    # run a new container with the following configuration:
    # -d: run in detached mode (background)
    # -v ollama:/root/.ollama: mount a docker volume for persistent storage
    # -p 11434:11434: map port 11434 from container to host
    # --name ollama: assign the name "ollama" to the container
    # ollama/ollama: use the official ollama image
    docker run -d -v ollama:/root/.ollama -p 11434:11434 --name ollama ollama/ollama
    
    # add a delay to ensure the container has time to initialize properly
    echo "Waiting for the container to fully start (10 seconds)..."
    sleep 10
    
    # download the llama3 model into the newly created container
    echo "Downloading the llama3 model..."
    docker exec -it ollama ollama pull llama3
else
    # container exists, check if it's currently running
    CONTAINER_RUNNING=$(docker ps --filter "name=ollama" --format "{{.ID}}")
    
    if [ -z "$CONTAINER_RUNNING" ]; then
        # container exists but is not running, so start it
        echo "Ollama container exists but is not running. Starting..."
        docker start ollama
        
        # add a shorter delay for container restart
        echo "Waiting for the container to start (5 seconds)..."
        sleep 5
    else
        # container is already running, no action needed
        echo "Ollama container is already running."
    fi
fi

# verify that the required llama3 model is available in the container
echo "Checking if the llama3 model is available..."
# execute the 'ollama list' command inside the container to get available models
MODEL_LIST=$(docker exec -it ollama ollama list)
if [[ $MODEL_LIST == *"llama3"* ]]; then
    # model found in the list, no action needed
    echo "Llama3 model found."
else
    # model not found, download it now
    echo "Downloading the llama3 model..."
    docker exec -it ollama ollama pull llama3
fi

# display success message and usage information
echo "Ollama is ready to use!"
echo "API URL: http://localhost:11434/api"
echo
echo "To test the connection, run: java TestOllama"
echo "To start the chat server, run: java ChatServer"

# show the container logs for monitoring and debugging purposes
# the -f flag follows the log output (like tail -f)
echo
echo "Displaying Ollama container logs (press Ctrl+C to exit):"
docker logs -f ollama