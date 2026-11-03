from pathlib import Path

import pytest

from codemind_ai.domain.source import ParseStatus
from codemind_ai.parsing.chunking import SourceChunker
from codemind_ai.parsing.service import SourceParsingService

PROJECT_ID = "22222222-2222-2222-2222-222222222222"
VERSION_ID = "11111111-1111-1111-1111-111111111111"


def test_python_parser_preserves_unicode_crlf_and_no_trailing_newline() -> None:
    source = "# 中文注释\r\n\r\ndef login():\r\n    return True"

    result = SourceParsingService().parse_content(
        "src/login.py", source.encode("utf-8"), PROJECT_ID, VERSION_ID
    )

    assert result.source_file.parse_status == ParseStatus.PARSED
    assert result.source_file.line_count == 4
    assert len(result.chunks) == 1
    assert result.chunks[0].text == source
    assert result.chunks[0].start_line == 1
    assert result.chunks[0].end_line == 4


def test_java_parser_uses_method_boundaries_for_long_classes() -> None:
    source = """public class LoginService {
    public boolean login(String user, String password) {
        return user.equals(password);
    }

    public void logout() {
        System.out.println("logout");
    }
}
"""
    service = SourceParsingService(SourceChunker(max_lines=4, overlap_lines=1))

    result = service.parse_content("src/LoginService.java", source.encode(), PROJECT_ID, VERSION_ID)

    assert result.source_file.parse_status == ParseStatus.PARSED
    assert len(result.chunks) >= 2
    for chunk in result.chunks:
        expected = "".join(source.splitlines(keepends=True)[chunk.start_line - 1 : chunk.end_line])
        assert chunk.text == expected
    assert any("login(String" in chunk.text for chunk in result.chunks)
    assert any("logout()" in chunk.text for chunk in result.chunks)


def test_syntax_error_uses_fallback_without_losing_source() -> None:
    source = "def broken(:\n    return 1\n# 最后一行"
    result = SourceParsingService().parse_content(
        "broken.py", source.encode(), PROJECT_ID, VERSION_ID
    )

    assert result.source_file.parse_status == ParseStatus.FALLBACK
    assert len(result.chunks) == 1
    assert result.chunks[0].text == source
    assert result.chunks[0].parse_status == ParseStatus.FALLBACK


def test_java_syntax_error_uses_fallback() -> None:
    source = "public class Broken {\n    void run( {\n}\n"

    result = SourceParsingService().parse_content(
        "Broken.java", source.encode(), PROJECT_ID, VERSION_ID
    )

    assert result.source_file.parse_status == ParseStatus.FALLBACK
    assert result.chunks[0].text == source


def test_empty_file_has_zero_lines_and_no_chunks() -> None:
    result = SourceParsingService().parse_content("empty.py", b"", PROJECT_ID, VERSION_ID)

    assert result.source_file.line_count == 0
    assert result.chunks == ()


def test_utf8_bom_does_not_change_line_mapping() -> None:
    result = SourceParsingService().parse_content(
        "demo.py", b"\xef\xbb\xbfdef run():\n    pass\n", PROJECT_ID, VERSION_ID
    )

    assert result.source_file.line_count == 2
    assert result.source_file.size_bytes == len(b"\xef\xbb\xbfdef run():\n    pass\n")
    assert result.chunks[0].start_line == 1
    assert result.chunks[0].text == "def run():\n    pass\n"


def test_non_utf8_content_fails_explicitly() -> None:
    with pytest.raises(UnicodeDecodeError):
        SourceParsingService().parse_content("bad.py", b"\xc3(", PROJECT_ID, VERSION_ID)


def test_parse_file_enforces_source_root(tmp_path: Path) -> None:
    source = tmp_path / "demo.py"
    source.write_text("def run():\n    return 1\n", encoding="utf-8")
    service = SourceParsingService()

    result = service.parse_file(tmp_path, "demo.py", PROJECT_ID, VERSION_ID)
    assert len(result.chunks) == 1

    with pytest.raises(ValueError, match="相对路径|非法片段|越界"):
        service.parse_file(tmp_path, "../outside.py", PROJECT_ID, VERSION_ID)
