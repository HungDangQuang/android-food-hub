-- 1) Page break before section 1 (after cover + TOC) and before the appendix.
-- 2) Scale every image to fit the A4 text block (17 cm wide, at most 23 cm tall).
local PB = pandoc.RawBlock('openxml', '<w:p><w:r><w:br w:type="page"/></w:r></w:p>')
local MAX_W, MAX_H = 17.0, 23.0

function Header(h)
  if h.level <= 2 then
    local t = pandoc.utils.stringify(h)
    if t:match('^1%.') or t:match('^Phụ lục') then return {PB, h} end
  end
end

function Image(img)
  local _, contents = pandoc.mediabag.fetch(img.src)
  if not contents then return nil end
  local ok, size = pcall(pandoc.image.size, contents)
  if not ok then return nil end
  local w = math.min(MAX_W, MAX_H * size.width / size.height)
  img.attributes.width = string.format('%.1fcm', w)
  return img
end
