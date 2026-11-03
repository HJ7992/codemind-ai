import hashlib
import uuid
from dataclasses import dataclass
from enum import StrEnum


class Language(StrEnum):
    JAVA = "JAVA"
    PYTHON = "PYTHON"

    @classmethod
    def from_path(cls, relative_path: str) -> "Language":
        lowered = relative_path.lower()
        if lowered.endswith(".java"):
            return cls.JAVA
        if lowered.endswith(".py"):
            return cls.PYTHON
        raise ValueError("仅支持 Java 和 Python 源码")


class ParseStatus(StrEnum):
    PARSED = "PARSED"
    FALLBACK = "FALLBACK"
    FAILED = "FAILED"


@dataclass(frozen=True, slots=True)
class CodeChunk:
    chunk_id: str
    project_id: str
    source_version_id: str
    relative_path: str
    language: Language
    start_line: int
    end_line: int
    text: str
    parse_status: ParseStatus
    content_sha256: str

    @classmethod
    def create(
        cls,
        project_id: str,
        source_version_id: str,
        relative_path: str,
        language: Language,
        start_line: int,
        end_line: int,
        text: str,
        parse_status: ParseStatus,
    ) -> "CodeChunk":
        if start_line < 1 or end_line < start_line:
            raise ValueError("代码块行号必须是从 1 开始的闭区间")
        content_sha256 = hashlib.sha256(text.encode("utf-8")).hexdigest()
        identity = (
            f"{project_id}:{source_version_id}:{relative_path}:"
            f"{start_line}:{end_line}:{content_sha256}"
        )
        return cls(
            chunk_id=str(uuid.uuid5(uuid.NAMESPACE_URL, identity)),
            project_id=project_id,
            source_version_id=source_version_id,
            relative_path=relative_path,
            language=language,
            start_line=start_line,
            end_line=end_line,
            text=text,
            parse_status=parse_status,
            content_sha256=content_sha256,
        )


@dataclass(frozen=True, slots=True)
class SourceFileMetadata:
    source_version_id: str
    relative_path: str
    language: Language
    size_bytes: int
    line_count: int
    sha256: str
    parse_status: ParseStatus


@dataclass(frozen=True, slots=True)
class ParseResult:
    source_file: SourceFileMetadata
    chunks: tuple[CodeChunk, ...]
