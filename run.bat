@echo off
REM Script para facilitar o uso do DocAI no Windows

setlocal enabledelayedexpansion

REM Define a variavel de ambiente CONTAINER_ENGINE como docker para ser usada pelo pg.clj
set CONTAINER_ENGINE=docker

:MAIN
if "%1"=="" goto HELP
if "%1"=="memory" goto MEMORY
if "%1"=="postgres" goto POSTGRES
if "%1"=="setup" goto SETUP
if "%1"=="docker-start" goto DOCKER_START
if "%1"=="docker_start" goto DOCKER_START
if "%1"=="docker-stop" goto DOCKER_STOP
if "%1"=="docker_stop" goto DOCKER_STOP
if "%1"=="help" goto HELP
echo X Opcao desconhecida: %1
goto HELP

:HELP
echo DocAI - Assistente RAG para Documentacao Tecnica
echo Uso: run.bat [opcao]
echo.
echo Opcoes:
echo   memory        Executa DocAI no modo memoria (TF-IDF)
echo   postgres      Executa DocAI no modo PostgreSQL
echo   setup         Configura o ambiente (inicia Docker, baixa modelos)
echo   docker-start  Inicia os containers Docker necessarios
echo   docker_start  Inicia os containers Docker necessarios (alternativo)
echo   docker-stop   Para os containers Docker
echo   docker_stop   Para os containers Docker (alternativo)
echo   help          Mostra esta ajuda
echo.
goto END

:CHECK_OLLAMA
echo Verificando se Ollama esta em execucao...
curl -s http://localhost:11434/api/version > nul 2>&1
if %ERRORLEVEL% EQU 0 (
    echo + Ollama esta em execucao!
) else (
    echo X Ollama nao esta em execucao. Por favor, inicie o Ollama com 'ollama serve'
    exit /b 1
)
exit /b 0

:CHECK_MODEL
echo Verificando se o modelo %1 esta disponivel...
ollama list | findstr "%1" > nul
if %ERRORLEVEL% EQU 0 (
    echo + Modelo %1 encontrado!
) else (
    echo ... Baixando modelo %1...
    ollama pull %1
)
exit /b 0

:CHECK_DOCKER
echo Verificando se Docker esta instalado...
where docker >nul 2>&1
if %ERRORLEVEL% EQU 0 (
    echo + Docker esta instalado!
    set CONTAINER_ENGINE=docker
    exit /b 0
) else (
    echo X Docker nao esta instalado. Visite https://www.docker.com/products/docker-desktop
    exit /b 1
)

:SETUP
echo * Configurando ambiente para DocAI...
call :CHECK_OLLAMA
call :CHECK_MODEL deepseek-r1
call :CHECK_DOCKER

if %ERRORLEVEL% EQU 0 (
    echo + Setup concluido!
) else (
    echo X Docker nao esta instalado. O modo PostgreSQL nao estara disponivel.
    echo + Setup concluido para modo de memoria apenas!
)
goto END

:MEMORY
call :CHECK_OLLAMA
call :CHECK_MODEL deepseek-r1
echo * Iniciando DocAI no modo memoria...
lein run
goto END

:POSTGRES
echo * Configurando ambiente para DocAI modo PostgreSQL...
call :CHECK_OLLAMA
call :CHECK_MODEL deepseek-r1
call :CHECK_DOCKER

if %ERRORLEVEL% NEQ 0 (
    echo X Docker nao esta instalado. O modo PostgreSQL nao esta disponivel.
    goto END
)

echo ... Reiniciando containers com Docker...
docker compose -f container-compose.yml down
docker compose -f container-compose.yml up -d

echo ... Esperando os servicos iniciarem (20 segundos)...
timeout /t 20 /nobreak > nul

echo ... Verificando modelos no conteiner Ollama...
echo ... Verificando se os modelos ja estao presentes...

docker exec pgai-ollama-1 ollama list | findstr "nomic-embed-text" > nul
if %ERRORLEVEL% NEQ 0 (
    echo ... Baixando o modelo nomic-embed-text dentro do conteiner...
    docker exec pgai-ollama-1 ollama pull nomic-embed-text
) else (
    echo + Modelo nomic-embed-text ja esta disponivel no conteiner
)

docker exec pgai-ollama-1 ollama list | findstr "deepseek-r1" > nul  
if %ERRORLEVEL% NEQ 0 (
    echo ... Baixando o modelo deepseek-r1 dentro do conteiner...
    docker exec pgai-ollama-1 ollama pull deepseek-r1
) else (
    echo + Modelo deepseek-r1 ja esta disponivel no conteiner
)

echo ... Verificando conectividade entre conteineres...
docker exec pgai-db-1 pg_isready -h localhost -p 5432 -U postgres
if %ERRORLEVEL% NEQ 0 (
    echo ! Conteiner PostgreSQL nao esta pronto. Esperando mais 10 segundos...
    timeout /t 10 /nobreak > nul
)

echo ... Verificando se o conteiner Ollama esta respondendo...
docker exec pgai-ollama-1 ollama list > nul
if %ERRORLEVEL% NEQ 0 (
    echo ! Conteiner Ollama nao esta respondendo. Esperando mais 10 segundos...
    timeout /t 10 /nobreak > nul
)

echo ... Iniciando DocAI no modo PostgreSQL...
echo + Todas as pre-configuracoes concluidas, iniciando aplicacao...
lein run --postgres
goto END

:DOCKER_START
call :CHECK_DOCKER
if %ERRORLEVEL% NEQ 0 goto END
echo ... Iniciando containers Docker...
docker compose -f container-compose.yml down
docker compose -f container-compose.yml up -d
echo ... Esperando servicos iniciarem (15 segundos)...
timeout /t 15 /nobreak > nul
echo ... Baixando modelos nos containers...
docker exec -i pgai-ollama-1 ollama list | findstr "nomic-embed-text" > nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo ... Baixando o modelo nomic-embed-text dentro do conteiner...
    docker exec -i pgai-ollama-1 sh -c "ollama pull nomic-embed-text" > nul 2>&1
) else (
    echo + Modelo nomic-embed-text ja esta disponivel no conteiner
)

docker exec -i pgai-ollama-1 ollama list | findstr "deepseek-r1" > nul 2>&1  
if %ERRORLEVEL% NEQ 0 (
    echo ... Baixando o modelo deepseek-r1 dentro do conteiner...
    docker exec -i pgai-ollama-1 sh -c "ollama pull deepseek-r1" > nul 2>&1
) else (
    echo + Modelo deepseek-r1 ja esta disponivel no conteiner
)
echo + Containers iniciados e modelos baixados!
goto END

:DOCKER_STOP
call :CHECK_DOCKER
if %ERRORLEVEL% NEQ 0 goto END
echo ... Parando containers Docker...
docker compose -f container-compose.yml down
echo + Containers parados!
goto END

:END
endlocal 