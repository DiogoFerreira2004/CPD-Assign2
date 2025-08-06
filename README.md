# Sistema de Chat Cliente-Servidor Distribuído em Java

## Índice
- [Descrição](#descrição-do-projeto)
- [Requisitos](#requisitos-do-sistema)
- [Arquitetura](#arquitetura-do-sistema)
- [Funcionalidades](#funcionalidades)
- [Instalação e Execução](#instruções-de-instalação-e-execução)
- [Utilização](#utilização-do-cliente)
- [Características Técnicas](#características-técnicas-implementadas)
- [Protocolo de Comunicação](#protocolo-de-comunicação)
- [Autores](#autores)

## Descrição do Projeto

Este projeto implementa um sistema de chat distribuído cliente-servidor em Java, utilizando comunicação TCP com suporte a SSL/TLS. O sistema permite que os utilizadores se autentiquem, enviem e recebam mensagens em tempo real em salas de chat, e inclui funcionalidades avançadas como salas de chat potenciadas por Inteligência Artificial através da integração com Ollama.
Este projeto foi desenvolvido como parte da unidade curricular de Computação Paralela e Distribuída (CPD), 2024/2025.

## Requisitos do Sistema

- Java SE 21 ou superior
- Docker (para execução do Ollama)
- 4GB de RAM (mínimo)
- 100MB de espaço em disco para a aplicação
- 4GB de espaço em disco adicional para o modelo Ollama

## Arquitetura do Sistema

O sistema utiliza uma arquitetura cliente-servidor onde:

1. **Servidor de Chat** - Gere a autenticação de utilizadores, criação e gestão de salas, e encaminhamento de mensagens.
2. **Cliente de Chat** - Interface para autenticação, listagem e associação a salas, e troca de mensagens.
3. **Conector IA** - Integração com o Ollama para salas com funcionalidades de IA.

### Componentes Principais:

| Componente | Descrição |
|------------|-----------|
| `ChatServer` | Gestor principal do servidor que aceita conexões e orquestra os outros componentes. |
| `ChatClient` | Aplicação cliente que conecta ao servidor e fornece interface de utilizador. |
| `UserManager` | Gestão de autenticação e registo de utilizadores. |
| `RoomManager` | Gestão da criação e utilização de salas de chat. |
| `AIConnector` | Interface com o modelo de linguagem Ollama para salas IA. |
| `Room` | Representa uma sala de chat e gere as mensagens e utilizadores conectados. |
| `ClientHandler` | Processa mensagens de um cliente específico no servidor. |
| `UserSession` | Gere as sessões dos utilizadores com tokens e expiração. |

## Funcionalidades

- Autenticação e registo de utilizadores
- Criação e gestão de salas de chat
- Comunicação em tempo real entre utilizadores
- Salas de chat potenciadas por IA (com integração Ollama)
- Tolerância a falhas (reconexão automática, persistência de sessão)
- Segurança básica (armazenamento de palavras-passe com hash e salt)
- Processamento concorrente (utilizando Java Virtual Threads)
- Comunicação segura com SSL/TLS
- Deteção de desconexões via heartbeats
- Sistema de filas de mensagens para evitar problemas com clientes lentos

## Instruções de Instalação e Execução

### Pré-requisitos

Ter instalado o:
- JDK 21 ou superior
- Docker (para o Ollama)

### Download

Clonar o repositório:

```bash
git clone https://gitlab.up.pt/classes/cpd/2425/t01/g12.git
cd g12
```

### Compilação

Para compilar o código, executar:

```bash
javac *.java
```

### Iniciar o Servidor

Para iniciar o servidor, executar:

```bash
java ChatServer
```

O servidor inicia por predefinição na porta 8989 se nenhuma porta for especificada.

### Iniciar o Cliente

Para iniciar um cliente, abrir um novo terminal e executar:

```bash
java ChatClient
```

Por predefinição, conecta a localhost:8989 se nenhum host/porta for especificado.

### Iniciar o Ollama (necessário para salas IA)

Para iniciar o Ollama, executar:

```bash
chmod +x start-ollama.sh
./start-ollama.sh
```

Este script verifica se o Docker está instalado, inicia um contentor Ollama, e descarrega o modelo `llama3` necessário para as salas de IA.

### Para estabelecer a conexão segura

Tornar o script executável:
```bash
chmod +x generate-ssl-keys.sh
```

Executar o script:
```bash
./generate-ssl-keys.sh
```

## Utilização do Cliente

### Autenticação

Ao iniciar o cliente, ser-lhe-á apresentado um menu de autenticação:

1. Login - Para utilizadores existentes
2. Registo - Para criar uma nova conta

Os utilizadores predefinidos são:
- Username: `diogo`, Password: `1234`
- Username: `alvaro`, Password: `1234`
- Username: `tomas`, Password: `1234`
- Username: `alice`, Password: `password1`
- Username: `bob`, Password: `password2`
- Username: `eve`, Password: `password3`

### Comandos Disponíveis

Após autenticação, estão disponíveis os seguintes comandos:

| Comando | Descrição |
|---------|-----------|
| `/rooms` | Lista todas as salas disponíveis |
| `/join <sala>` | Entra numa sala existente |
| `/create <sala>` | Cria uma nova sala normal |
| `/create_ai <sala>\|<prompt>` | Cria uma sala com IA, onde o prompt define o comportamento da IA |
| `/leave` | Sai da sala atual |
| `/logout` | Termina a sessão |
| `/test_disconnect` | (Apenas para testes) Simula uma desconexão para testar a reconexão automática |

### Exemplos de Comandos

```
/create my_room
/create_ai ai_chat|Atua como um especialista em Java e ajuda a responder a questões sobre programação
/join my_room
Olá a todos!
/leave
/logout
```

### Envio de Mensagens

Para enviar uma mensagem, basta digitar o texto e pressionar Enter quando estiver dentro de uma sala.

## Características Técnicas Implementadas

### Concorrência

O sistema implementa concorrência através de:
- Utilização de Java Virtual Threads para minimizar a sobrecarga
- Bloqueios de leitura/escrita (`java.util.concurrent.locks.ReadWriteLock`) para acesso seguro a estruturas de dados partilhadas
- Implementações próprias de sincronização sem recorrer a coleções thread-safe de `java.util.concurrent`
- Filas de mensagens para evitar que clientes lentos afetem o desempenho global
- Processamento assíncrono de mensagens e comandos

### Tolerância a Falhas

O sistema implementa mecanismos de tolerância a falhas:
- Reconexão automática do cliente em caso de falha na ligação
- Tokens de sessão para manter o estado do utilizador após reconexão
- Expiração de tokens para melhorar a segurança
- Heartbeats para deteção de desconexões
- Persistência de dados de utilizadores e sessões
- Backoff exponencial nas tentativas de reconexão

### Segurança

- Palavras-passe armazenadas com hash SHA-256 e salt
- Tokens de sessão gerados aleatoriamente
- Validação de entrada para prevenir injeções
- Comunicação encriptada com SSL/TLS
- Gestão segura de tokens de sessão

### Desempenho

- Cache de respostas da IA para melhorar o desempenho
- Processamento assíncrono de mensagens
- Limite de histórico para evitar o consumo excessivo de memória
- Otimização de consultas ao serviço Ollama

## Protocolo de Comunicação

O sistema utiliza um protocolo baseado em texto para comunicação:

### Comandos do Cliente para o Servidor

| Comando | Formato | Descrição |
|---------|---------|-----------|
| Login | `LOGIN <utilizador> <palavra-passe>` | Autenticação de utilizador |
| Registo | `REGISTER <utilizador> <palavra-passe>` | Registo de novo utilizador |
| Reconexão | `RECONNECT <token> [sala]` | Reconexão com token de sessão e opcionalmente a sala anterior |
| Listar salas | `LIST_ROOMS` | Solicita lista de salas disponíveis |
| Entrar numa sala | `JOIN_ROOM <sala>` | Entrada numa sala existente |
| Criar sala | `CREATE_ROOM <sala>` | Criação de sala normal |
| Criar sala IA | `CREATE_AI_ROOM <sala>\|<prompt>` | Criação de sala com IA |
| Enviar mensagem | `MESSAGE <texto>` | Envio de mensagem para a sala atual |
| Sair da sala | `LEAVE_ROOM` | Saída da sala atual |
| Terminar sessão | `LOGOUT` | Termina a sessão atual |
| Resposta Heartbeat | `HEARTBEAT_ACK` | Resposta ao heartbeat do servidor |

### Respostas do Servidor para o Cliente

| Resposta | Formato | Descrição |
|----------|---------|-----------|
| Autenticação necessária | `AUTH_REQUIRED` | Solicita autenticação ao cliente |
| Autenticação sucedida | `AUTH_SUCCESS <utilizador> <token>` | Autenticação bem-sucedida |
| Autenticação falhou | `AUTH_FAILED` | Autenticação falhada |
| Registo sucedido | `REGISTER_SUCCESS` | Registo bem-sucedido |
| Registo falhou | `REGISTER_FAILED <motivo>` | Registo falhado |
| Reconexão sucedida | `RECONNECT_SUCCESS <utilizador> [sala]` | Reconexão bem-sucedida, opcionalmente com a sala |
| Sessão expirada | `SESSION_EXPIRED` | Token de sessão expirado |
| Lista de salas | `ROOM_LIST <salas>` | Lista de salas disponíveis |
| Entrada na sala | `JOINED_ROOM <sala>` | Entrada na sala bem-sucedida |
| Saída da sala | `LEFT_ROOM` | Saída da sala |
| Sala criada | `ROOM_CREATED <sala>` | Sala criada com sucesso |
| Sala IA criada | `AI_ROOM_CREATED <sala>` | Sala IA criada com sucesso |
| Mensagem da sala | `ROOM_MESSAGE <mensagem>` | Mensagem recebida numa sala |
| Erro | `ERROR <motivo>` | Erro durante processamento |
| Sessão terminada | `LOGGED_OUT` | Saída do sistema |
| Verificação conexão | `HEARTBEAT` | Verificação de conexão |

## Autores

- Diogo Miguel Fernandes Ferreira (up202205295@up.pt)
- Álvaro Luís Dias Amaral Alvim Torres (up202208954@up.pt)
- Tomás Ferreira de Oliveira (up202208415@up.pt)