# Third-party notices

## Touhou Little Maid (TartaricAcid/TouhouLittleMaid)

- Upstream: https://github.com/TartaricAcid/TouhouLittleMaid (1.21 branch, commit 207647c85740b1d0971de6040d6dd57cea62528c)
- Use here: references for "keep bottom root, harvest upper sugar-cane segments" and current-darkness standing-torch selection (MaidTorchMoveTask / TaskTorch, raw brightness < 9). AutoLightSession / LightDeficitPlanner use original bounded build integration, not upstream setBlock or inventory shrink; no assets copied.
- Implementation in this repository (`SugarCaneCompanionTask`, `SugarCaneHarvestSession`) is original; no upstream code or assets copied.
- License: MIT. Full text (upstream):
  Copyright (c) 2019-2024 tartaric_acid, for the code part

  Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
  documentation files (the "Software"), to deal in the Software without restriction, including without limitation
  the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software,
  and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

  The above copyright notice and this permission notice shall be included in all copies or substantial portions
  of the Software.

  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO
  THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT,
  TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
