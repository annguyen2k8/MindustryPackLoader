## 2. Checklist theo dõi tiến độ

### P0 — Tính đúng đắn và an toàn dữ liệu

- [x] H1 — Native map import tạo sai tên file và conflict policy.
- [x] H2 — Schematic import báo SUCCESS dù ghi file thất bại.
- [x] H4 — Validation chấp nhận file malformed.
- [x] H5 — OVERWRITE có thể làm mất dữ liệu cũ.
- [x] M1 — Scan race và thiếu cancellation.
- [x] M2 — Filter state rò rỉ giữa các phiên.
- [x] M3 — Item bị ẩn vẫn có thể được import.
- [x] M4 — Corrupt ZIP bị phân loại sai.
- [x] M5 — SAF ZIP cache bị leak.
- [x] M6 — Detail label bị ép thành 12×12 px.
- [x] M8 — Open Maps mở sai màn hình.
- [x] Regression tests cho native import, malformed files và overwrite failure.
- [x] Full test suite và kiểm tra working tree.

### P1 — Hiệu năng, UX và độ ổn định

- [x] H3 — Background import, progress và cancellation.
- [x] M7 — Responsive result dialog.
- [x] Smart selection.
- [x] Search/filter.
- [x] Recent sources / Import again.
- [x] Partial scan failure và permission messaging.
- [x] M9 — Giới hạn tài nguyên ZIP.
- [x] M10 — Chống directory cycle và recursion quá sâu.
- [x] M11 — Nhận diện ZIP entry không có extension.
- [x] Localization cho nhãn storage còn hardcode.
- [x] UI testing trên màn hình nhỏ.

### P2 — Giảm thao tác

- [x] Quick Import một chạm.
- [x] Multi-source queue.
- [x] Per-type conflict policy presets.
- [x] Dry-run conflict preview.
- [x] Undo/backup.
- [x] Content-hash duplicate detection.

### P3 — Mở rộng và compatibility

- [~] Android Share/Open-with và desktop drag-and-drop.
- [x] Preview thumbnail và metadata.
- [x] Export import report.
- [x] CI matrix v160/v160.7 và runtime smoke test.

### P4 — Tối ưu hóa In-Game Workflow

- [x] P4.1 — Direct in-game schematic placement.

## Quy ước cập nhật

- `[ ]` Chưa hoàn thành.
- `[x]` Đã hoàn thành và có bằng chứng nghiệm thu.
- `[~]` Đang triển khai hoặc mới hoàn thành một phần.

**Nguyên tắc:** Một mục = một checkbox. Chi tiết triển khai, bằng chứng và test chỉ ghi trong phần mô tả của mục tương ứng, không tạo thêm checkbox con.

Khi hoàn thành, cập nhật checkbox tại tracker, trạng thái trong phần chi tiết lỗi và nhật ký commit. Không đánh dấu hoàn tất chỉ vì code đã sửa nếu chưa có bằng chứng kiểm thử phù hợp.

## 3. Nhật ký kiểm tra nghiệm thu (Closure Audits)

### P3 Closure Audit (2026-11-07)
- **Báo cáo chi tiết:** `P3_CLOSURE_AUDIT.md` (commit `a30275b`+).
- **Tổng số tests:** 203 passing (0 failures, 20 test classes) trên cả Mindustry v160 và v160.7.
- **Phân loại kết quả:**
  - *Verified:* P0 (13/13), P1 (11/11), P2 (6/6), Desktop Drag-and-drop, P3.2 Preview metadata/thumbnails, P3.3 Report Export (JSON/CSV), P3.4 CI Matrix & v160.7 Runtime Smoke Test.
  - *Partially verified:* Mindustry v160.0 Standalone GUI Smoke Test (203/203 unit tests đạt trên v160 JAR; chưa chạy GUI thực tế vì host chỉ cài bản v160.7).
  - *Blocked:* Android Share/Open-with (giới hạn kiến trúc Android OS manifest — cần can thiệp host APK Mindustry; Desktop Drag-and-Drop đã verified).
- **Kết luận:** Milestone P3 hoàn tất nghiệm thu và đủ điều kiện sẵn sàng mở P4.
