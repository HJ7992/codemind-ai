import hashlib
from pathlib import Path

from codemind_ai.domain.source import Language, ParseResult, SourceFileMetadata
from codemind_ai.parsing.chunking import SourceChunker
from codemind_ai.parsing.java_structure import JavaStructureParser
from codemind_ai.parsing.python_structure import PythonStructureParser


class SourceParsingService:
    def __init__(self, chunker: SourceChunker | None = None) -> None:
        self.chunker = chunker or SourceChunker()
        self.parsers = {
            Language.JAVA: JavaStructureParser(),
            Language.PYTHON: PythonStructureParser(),
        }

    def parse_file(
        self, source_root: Path, relative_path: str, project_id: str, source_version_id: str
    ) -> ParseResult:
        root = source_root.resolve()
        relative = self._validate_relative_path(relative_path)
        target = (root / relative).resolve()
        if not target.is_relative_to(root) or not target.is_file():
            raise ValueError("源码文件不存在或路径越界")
        return self.parse_content(relative, target.read_bytes(), project_id, source_version_id)

    def parse_content(
        self, relative_path: str, content: bytes, project_id: str, source_version_id: str
    ) -> ParseResult:
        relative = self._validate_relative_path(relative_path)
        if not project_id.strip() or not source_version_id.strip():
            raise ValueError("项目 ID 和源码版本 ID 不能为空")
        language = Language.from_path(relative)
        text = content.decode("utf-8-sig")
        parse_status, spans = self.parsers[language].parse(text)
        chunks = self.chunker.chunk(
            text=text,
            spans=spans,
            project_id=project_id,
            source_version_id=source_version_id,
            relative_path=relative,
            language=language,
            parse_status=parse_status,
        )
        metadata = SourceFileMetadata(
            source_version_id=source_version_id,
            relative_path=relative,
            language=language,
            size_bytes=len(content),
            line_count=len(text.splitlines()),
            sha256=hashlib.sha256(content).hexdigest(),
            parse_status=parse_status,
        )
        return ParseResult(source_file=metadata, chunks=chunks)

    @staticmethod
    def _validate_relative_path(relative_path: str) -> str:
        if (
            not relative_path
            or "\x00" in relative_path
            or "\\" in relative_path
            or relative_path.startswith(("/", "//"))
            or Path(relative_path).is_absolute()
            or Path(relative_path).drive
        ):
            raise ValueError("源码路径必须是规范化相对路径")
        normalized = Path(relative_path).as_posix()
        if normalized != relative_path or any(
            part in {"", ".", ".."} for part in normalized.split("/")
        ):
            raise ValueError("源码路径包含非法片段")
        return normalized
