#!/bin/bash

# Script para facilitar o uso do DocAI

set -e

# Define a variável de ambiente CONTAINER_ENGINE como podman para ser usada pelo pg.clj
export CONTAINER_ENGINE=podman

function show_help {
    echo "DocAI - Assistente RAG para Documentação Técnica"
    echo "Uso: ./run.sh [opção]"
    echo
    echo "Opções:"
    echo "  memory        Executa DocAI no modo memória (TF-IDF)"
    echo "  postgres      Executa DocAI no modo PostgreSQL"
    echo "  setup         Configura o ambiente (inicia Podman, baixa modelos)"
    echo "  podman-start  Inicia os containers Podman necessários"
    echo "  podman_start  Inicia os containers Podman necessários (alternativo)"
    echo "  podman-stop   Para os containers Podman"
    echo "  podman_stop   Para os containers Podman (alternativo)"
    echo "  help          Mostra esta ajuda"
    echo
}

function check_ollama {
    echo "Verificando se Ollama está em execução..."
    curl -s http://localhost:11434/api/version > /dev/null
    if [ $? -eq 0 ]; then
        echo "✅ Ollama está em execução!"
    else
        echo "❌ Ollama não está em execução. Por favor, inicie o Ollama com 'ollama serve'"
        exit 1
    fi
}

function check_model {
    local model=$1
    echo "Verificando se o modelo $model está disponível..."
    if ollama list | grep -q "$model"; then
        echo "✅ Modelo $model encontrado!"
    else
        echo "⏳ Baixando modelo $model..."
        ollama pull $model
    fi
}

function check_podman {
    echo "Verificando se Podman está instalado..."
    if command -v podman &> /dev/null; then
        echo "✅ Podman está instalado!"
        return 0
    else
        echo "❌ Podman não está instalado. Visite https://podman.io/getting-started/installation para instruções de instalação."
        return 1
    fi
}

function setup_environment {
    echo "🚀 Configurando ambiente para DocAI..."
    
    # Verificar Ollama
    check_ollama
    
    # Verificar modelos
    check_model "deepseek-r1"
    
    # Iniciar Podman se modo PostgreSQL
    if [ "$1" == "postgres" ]; then
        if check_podman; then
            echo "⏳ Iniciando containers Podman..."
            podman-compose up -d
            
            echo "⏳ Esperando os serviços iniciarem..."
            sleep 5
            
            echo "⏳ Verificando modelo de embeddings..."
            podman-compose exec -T ollama ollama list | grep -q "nomic-embed-text" || podman-compose exec -T ollama ollama pull nomic-embed-text
        else
            echo "❌ Não é possível iniciar o modo PostgreSQL sem o Podman."
            exit 1
        fi
    fi
    
    echo "✅ Ambiente configurado com sucesso!"
}

# Verificar argumentos
if [ $# -eq 0 ]; then
    show_help
    exit 0
fi

case "$1" in
    memory)
        check_ollama
        check_model "deepseek-r1"
        echo "🚀 Iniciando DocAI no modo memória..."
        lein run
        ;;
    postgres)
        setup_environment "postgres"
        echo "🚀 Iniciando DocAI no modo PostgreSQL..."
        lein run --postgres
        ;;
    setup)
        setup_environment
        echo "✅ Setup concluído!"
        ;;
    podman-start|podman_start)
        if check_podman; then
            echo "⏳ Iniciando containers Podman..."
            podman-compose up -d
            echo "✅ Containers iniciados!"
        else
            exit 1
        fi
        ;;
    podman-stop|podman_stop)
        if check_podman; then
            echo "⏳ Parando containers Podman..."
            podman-compose down
            echo "✅ Containers parados!"
        else
            exit 1
        fi
        ;;
    help)
        show_help
        ;;
    *)
        echo "❌ Opção desconhecida: $1"
        show_help
        exit 1
        ;;
esac

exit 0 