# FEUP - Parallel and Distributed Computing - 2024/2025
> Curricular Unit: CPD - [Computação Paralela e Distribuída](https://sigarra.up.pt/feup/pt/ucurr_geral.ficha_uc_view?pv_ocorrencia_id=541893)
## 3rd Year - 1st Semester Project
### Brief description:
This project implements a distributed client-server chat system in Java, designed to provide real-time communication capabilities with advanced features including AI-powered chat rooms. Built using Java SE 21 with TCP/SSL communication and Docker integration for Ollama AI services, it offers a robust and secure messaging platform that supports concurrent users and fault-tolerant operations.

The system enables users to authenticate, create and join chat rooms, exchange messages in real-time, and interact with AI-powered chat assistants. Key features include automatic reconnection mechanisms, session persistence with secure token management, SSL/TLS encrypted communication, and concurrent processing using Java Virtual Threads. The platform also incorporates comprehensive security measures with password hashing, input validation, and heartbeat-based connection monitoring to ensure reliable and secure communication.

I hope you find it useful!

---

# Distributed Client-Server Chat System in Java

## Table of Contents
- [Project Description](#project-description)
- [System Requirements](#system-requirements)
- [System Architecture](#system-architecture)
- [Features](#features)
- [Installation and Execution](#installation-and-execution-instructions)
- [Client Usage](#client-usage)
- [Technical Characteristics](#implemented-technical-characteristics)
- [Communication Protocol](#communication-protocol)
- [Authors](#authors)

## Project Description

This project implements a distributed client-server chat system in Java, using TCP communication with SSL/TLS support. The system allows users to authenticate, send and receive real-time messages in chat rooms, and includes advanced features such as AI-powered chat rooms through integration with Ollama.
This project was developed as part of the Parallel and Distributed Computing (CPD) course, 2024/2025.

## System Requirements

- Java SE 21 or higher
- Docker (for Ollama execution)
- 4GB RAM (minimum)
- 100MB disk space for the application
- 4GB additional disk space for the Ollama model

## System Architecture

The system uses a client-server architecture where:

1. **Chat Server** - Manages user authentication, room creation and management, and message routing.
2. **Chat Client** - Interface for authentication, room listing and joining, and message exchange.
3. **AI Connector** - Integration with Ollama for AI-enhanced rooms.

### Main Components:

| Component | Description |
|-----------|-------------|
| `ChatServer` | Main server manager that accepts connections and orchestrates other components. |
| `ChatClient` | Client application that connects to the server and provides user interface. |
| `UserManager` | User authentication and registration management. |
| `RoomManager` | Chat room creation and usage management. |
| `AIConnector` | Interface with the Ollama language model for AI rooms. |
| `Room` | Represents a chat room and manages messages and connected users. |
| `ClientHandler` | Processes messages from a specific client on the server. |
| `UserSession` | Manages user sessions with tokens and expiration. |

## Features

- User authentication and registration
- Chat room creation and management
- Real-time communication between users
- AI-powered chat rooms (with Ollama integration)
- Fault tolerance (automatic reconnection, session persistence)
- Basic security (password storage with hash and salt)
- Concurrent processing (using Java Virtual Threads)
- Secure communication with SSL/TLS
- Disconnection detection via heartbeats
- Message queue system to avoid issues with slow clients

## Installation and Execution Instructions

### Prerequisites

Must have installed:
- JDK 21 or higher
- Docker (for Ollama)

### Download

Clone the repository:

```bash
git clone https://gitlab.up.pt/classes/cpd/2425/t01/g12.git
cd g12
```

### Compilation

To compile the code, run:

```bash
javac *.java
```

### Start the Server

To start the server, run:

```bash
java ChatServer
```

The server starts by default on port 8989 if no port is specified.

### Start the Client

To start a client, open a new terminal and run:

```bash
java ChatClient
```

By default, connects to localhost:8989 if no host/port is specified.

### Start Ollama (required for AI rooms)

To start Ollama, run:

```bash
chmod +x start-ollama.sh
./start-ollama.sh
```

This script checks if Docker is installed, starts an Ollama container, and downloads the `llama3` model needed for AI rooms.

### To establish secure connection

Make the script executable:
```bash
chmod +x generate-ssl-keys.sh
```

Run the script:
```bash
./generate-ssl-keys.sh
```

## Client Usage

### Authentication

When starting the client, you will be presented with an authentication menu:

1. Login - For existing users
2. Register - To create a new account

The default users are:
- Username: `diogo`, Password: `1234`
- Username: `alvaro`, Password: `1234`
- Username: `tomas`, Password: `1234`
- Username: `alice`, Password: `password1`
- Username: `bob`, Password: `password2`
- Username: `eve`, Password: `password3`

### Available Commands

After authentication, the following commands are available:

| Command | Description |
|---------|-------------|
| `/rooms` | List all available rooms |
| `/join <room>` | Join an existing room |
| `/create <room>` | Create a new normal room |
| `/create_ai <room>\|<prompt>` | Create an AI room, where the prompt defines the AI behavior |
| `/leave` | Leave the current room |
| `/logout` | End the session |
| `/test_disconnect` | (Testing only) Simulate a disconnection to test automatic reconnection |

### Command Examples

```
/create my_room
/create_ai ai_chat|Act as a Java expert and help answer programming questions
/join my_room
Hello everyone!
/leave
/logout
```

### Sending Messages

To send a message, simply type the text and press Enter when inside a room.

## Implemented Technical Characteristics

### Concurrency

The system implements concurrency through:
- Use of Java Virtual Threads to minimize overhead
- Read/write locks (`java.util.concurrent.locks.ReadWriteLock`) for safe access to shared data structures
- Custom synchronization implementations without relying on thread-safe collections from `java.util.concurrent`
- Message queues to prevent slow clients from affecting global performance
- Asynchronous processing of messages and commands

### Fault Tolerance

The system implements fault tolerance mechanisms:
- Automatic client reconnection in case of connection failure
- Session tokens to maintain user state after reconnection
- Token expiration to improve security
- Heartbeats for disconnection detection
- User data and session persistence
- Exponential backoff in reconnection attempts

### Security

- Passwords stored with SHA-256 hash and salt
- Randomly generated session tokens
- Input validation to prevent injections
- Encrypted communication with SSL/TLS
- Secure session token management

### Performance

- AI response caching to improve performance
- Asynchronous message processing
- History limit to avoid excessive memory consumption
- Optimization of Ollama service queries

## Communication Protocol

The system uses a text-based protocol for communication:

### Client to Server Commands

| Command | Format | Description |
|---------|--------|-------------|
| Login | `LOGIN <user> <password>` | User authentication |
| Register | `REGISTER <user> <password>` | New user registration |
| Reconnect | `RECONNECT <token> [room]` | Reconnection with session token and optionally the previous room |
| List rooms | `LIST_ROOMS` | Request list of available rooms |
| Join room | `JOIN_ROOM <room>` | Join an existing room |
| Create room | `CREATE_ROOM <room>` | Create a normal room |
| Create AI room | `CREATE_AI_ROOM <room>\|<prompt>` | Create an AI room |
| Send message | `MESSAGE <text>` | Send message to current room |
| Leave room | `LEAVE_ROOM` | Leave current room |
| End session | `LOGOUT` | End current session |
| Heartbeat response | `HEARTBEAT_ACK` | Response to server heartbeat |

### Server to Client Responses

| Response | Format | Description |
|----------|--------|-------------|
| Authentication required | `AUTH_REQUIRED` | Request authentication from client |
| Authentication successful | `AUTH_SUCCESS <user> <token>` | Successful authentication |
| Authentication failed | `AUTH_FAILED` | Failed authentication |
| Registration successful | `REGISTER_SUCCESS` | Successful registration |
| Registration failed | `REGISTER_FAILED <reason>` | Failed registration |
| Reconnection successful | `RECONNECT_SUCCESS <user> [room]` | Successful reconnection, optionally with room |
| Session expired | `SESSION_EXPIRED` | Session token expired |
| Room list | `ROOM_LIST <rooms>` | List of available rooms |
| Joined room | `JOINED_ROOM <room>` | Successful room entry |
| Left room | `LEFT_ROOM` | Left room |
| Room created | `ROOM_CREATED <room>` | Room created successfully |
| AI room created | `AI_ROOM_CREATED <room>` | AI room created successfully |
| Room message | `ROOM_MESSAGE <message>` | Message received in room |
| Error | `ERROR <reason>` | Error during processing |
| Session ended | `LOGGED_OUT` | System logout |
| Connection check | `HEARTBEAT` | Connection verification |

## Authors

- Diogo Miguel Fernandes Ferreira (up202205295@up.pt)
- Álvaro Luís Dias Amaral Alvim Torres (up202208954@up.pt)
- Tomás Ferreira de Oliveira (up202208415@up.pt)
