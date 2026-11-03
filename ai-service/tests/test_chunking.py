from codemind_ai.domain.source import Language, ParseStatus
from codemind_ai.parsing.chunking import SourceChunker
from codemind_ai.parsing.models import SyntaxSpan

PROJECT_ID = "22222222-2222-2222-2222-222222222222"
VERSION_ID = "11111111-1111-1111-1111-111111111111"


def test_fixed_windows_have_exact_closed_line_ranges() -> None:
    text = "".join(f"line {number}\n" for number in range(1, 11))
    chunker = SourceChunker(max_lines=4, overlap_lines=1)

    chunks = chunker.chunk(
        text, [], PROJECT_ID, VERSION_ID, "demo.py", Language.PYTHON, ParseStatus.FALLBACK
    )

    assert [(chunk.start_line, chunk.end_line) for chunk in chunks] == [(1, 4), (4, 7), (7, 10)]
    assert chunks[0].text == "line 1\nline 2\nline 3\nline 4\n"
    assert chunks[-1].text == "line 7\nline 8\nline 9\nline 10\n"


def test_structure_boundaries_avoid_duplicate_code() -> None:
    text = (
        "class Demo:\n    def first(self):\n        pass\n\n    def second(self):\n        pass\n"
    )
    spans = [SyntaxSpan(1, 6, (SyntaxSpan(2, 3), SyntaxSpan(5, 6)))]
    chunker = SourceChunker(max_lines=3, overlap_lines=1)

    chunks = chunker.chunk(
        text, spans, PROJECT_ID, VERSION_ID, "demo.py", Language.PYTHON, ParseStatus.PARSED
    )

    assert [(chunk.start_line, chunk.end_line) for chunk in chunks] == [
        (1, 1),
        (2, 3),
        (4, 4),
        (5, 6),
    ]
    assert [chunk.text for chunk in chunks] == [
        "class Demo:\n",
        "    def first(self):\n        pass\n",
        "\n",
        "    def second(self):\n        pass\n",
    ]


def test_chunk_ids_are_deterministic() -> None:
    text = "def run():\n    return 1\n"
    chunker = SourceChunker()

    first = chunker.chunk(
        text, [], PROJECT_ID, VERSION_ID, "demo.py", Language.PYTHON, ParseStatus.PARSED
    )
    second = chunker.chunk(
        text, [], PROJECT_ID, VERSION_ID, "demo.py", Language.PYTHON, ParseStatus.PARSED
    )
    other_version = chunker.chunk(
        text,
        [],
        PROJECT_ID,
        "33333333-3333-3333-3333-333333333333",
        "demo.py",
        Language.PYTHON,
        ParseStatus.PARSED,
    )

    assert [chunk.chunk_id for chunk in first] == [chunk.chunk_id for chunk in second]
    assert first[0].chunk_id != other_version[0].chunk_id
