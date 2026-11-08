"""Shared embedding and pgvector retrieval for the public knowledge catalog."""

from contextlib import contextmanager
import json
from math import isfinite
import os
from pathlib import Path


HERE = Path(__file__).resolve().parent
CATALOG = HERE.parents[1] / "src/main/resources/knowledge/public-sources.json"
DEFAULT_MODEL = "sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2"
DEFAULT_MODEL_CACHE = HERE.parents[2] / "tmp/quant-knowledge-eval/models"


def validate_vector(vector, dimension):
    """Reject unusable cosine vectors before either lookup."""
    if len(vector) != dimension or not any(vector) or not all(map(isfinite, vector)):
        raise ValueError("The embedding model returned an invalid vector")


def query_vector(model, question, dimension):
    """Encode the original question with the same model as the public passages."""
    query, = model.query_embed(question)
    vector = query.tolist()
    validate_vector(vector, dimension)
    return vector


def load_index(sources, model_name, model_cache, allow_download=False, *, model_path=None):
    """Load the approved embedding model and encode the public passages once."""
    os.environ["HF_HUB_DISABLE_IMPLICIT_TOKEN"] = "1"
    os.environ["HF_HUB_DISABLE_TELEMETRY"] = "1"

    from fastembed import TextEmbedding

    model_location = ({"cache_dir": str(model_cache)} if model_path is None
                      else {"specific_model_path": str(model_path)})
    model = TextEmbedding(
        model_name=model_name,
        local_files_only=not allow_download,
        threads=2,
        **model_location,
    )
    passages = [f"{source['title']}\n{source['content']}" for source in sources]
    vectors = [vector.tolist() for vector in model.passage_embed(passages)]
    dimension = len(vectors[0])
    indexed = {source["id"]: vector for source, vector in zip(sources, vectors, strict=True)}
    for vector in vectors:
        validate_vector(vector, dimension)
    return model, indexed, dimension


@contextmanager
def open_pgvector(indexed, port):
    """Index and search only the dedicated connection's temporary table."""
    import psycopg

    options = {
        "host": "127.0.0.1",
        "hostaddr": "127.0.0.1",
        "port": port,
        "dbname": "quant_knowledge_eval",
        "user": "knowledge_eval",
        "password": os.environ["KNOWLEDGE_EVAL_PG_PASSWORD"],
        "connect_timeout": 5,
        "options": "-c statement_timeout=1000 -c search_path=pg_catalog,public",
    }
    with psycopg.connect(autocommit=True, **options) as connection, connection.cursor() as cursor:
        cursor.execute("SELECT 1 FROM pg_extension WHERE extname = 'vector'")
        if cursor.fetchone() is None:
            raise RuntimeError("Enable pgvector in the dedicated eval database first")

        cursor.execute("""
            CREATE TEMP TABLE knowledge_eval_chunks (
                source_id text PRIMARY KEY,
                embedding public.vector NOT NULL
            )
            """)
        cursor.executemany(
            "INSERT INTO pg_temp.knowledge_eval_chunks VALUES (%s, %s::public.vector)",
            [(source_id, json.dumps(vector)) for source_id, vector in indexed.items()],
        )

        def lookup(query, top_k):
            cursor.execute("""
                SELECT source_id
                FROM pg_temp.knowledge_eval_chunks
                ORDER BY embedding OPERATOR(public.<=>) %s::public.vector, source_id
                LIMIT %s
                """, (json.dumps(query), top_k))
            return [row[0] for row in cursor.fetchall()]

        yield lookup
