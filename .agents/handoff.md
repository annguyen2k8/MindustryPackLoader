# Current handoff

> Replace this file at the end of each task. Keep only the current state; do not append an endless history.

- **Status:** complete
- **Last updated:** 2026-11-09 22:48 +07:00
- **Updated by:** Antigravity / Gemini 3.8 Flash (High)
- **Task:** Git Branch Restructuring (Adopt `main` + `dev` model).
- **Branch:** dev; upstream origin/dev configured.

## Results

- [verified] Git Branch Architecture Restructuring:
  - `dev` branch được tạo từ commit `1acc8fe` (HEAD), chứa toàn bộ 47 commits chuẩn bị cho `v1.1.0`.
  - `main` branch được tạo từ commit `57135c6` (tag `v1.0.1` — latest stable release).
  - Cả hai branch `main` và `dev` đã được push lên `origin` với upstream tracking đầy đủ.
- [verified] GitHub Configuration:
  - Default branch trên GitHub đã được chuyển thành công từ `master` sang `main` (`gh repo edit --default-branch main`).
  - Kiểm tra GitHub Rulesets & Branch Protections: Ruleset Mindustry ở trạng thái `disabled`, không có restriction cản trở.
  - Releases (`v1.0.1` latest, `v1.0.0`) và Git tags (`v1.0.1`, `v1.0.0`) được bảo toàn 100%, không bị ảnh hưởng.
  - GitHub Actions permissions: Enabled (`allowed_actions: all`). Workflows `commitTest.yml` và `release.yml` sẵn sàng hoạt động trên cả hai nhánh.
- [verified] Branch Preservation:
  - `master` branch (cục bộ và remote `origin/master`) được giữ nguyên vẹn 100%, không bị xóa hay rewrite lịch sử.
- [verified] Documentation:
  - Cập nhật quy ước phân nhánh trong `AGENTS.md` (mô hình `main` + `dev`, release via merge & tag).

## Remaining

- Không còn tồn đọng trong quá trình tái cấu trúc branch.

## Next steps

1. Mọi công việc phát triển tính năng mới tiếp tục được thực hiện trên nhánh `dev`.
2. Khi sẵn sàng phát hành `v1.1.0`:
   - Chạy toàn bộ test suite và verify build.
   - Merge `dev` vào `main`.
   - Tạo tag `v1.1.0` trên `main` và kích hoạt release workflow.
3. Đánh giá và quyết định thời điểm dọn dẹp hoặc lưu trữ nhánh `master` cũ (nếu không còn nhu cầu sử dụng).

## Open questions

- Không có.

## Verification

- `git rev-parse main` = `57135c6b41295db2f483b3e0c03405c93c4a2a19` (khớp với tag `v1.0.1`).
- `git rev-parse dev` = `1acc8fe74aa176a788183b169202caa21dc0783b` (giữ đủ 47 commits từ `v1.0.1`).
- `git rev-parse master` = `1acc8fe74aa176a788183b169202caa21dc0783b` (nguyên vẹn).
- `gh repo view --json defaultBranchRef` = `{"defaultBranchRef":{"name":"main"}}`.
- `gh release list` = `v1.0.1` (Latest), `v1.0.0` (nguyên vẹn).
