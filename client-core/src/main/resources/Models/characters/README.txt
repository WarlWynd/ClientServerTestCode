Character model assets — drop GLB files here.

Expected files:
  warrior.glb
  ranger.glb
  mage.glb

Required animation clip names (case-sensitive):
  idle
  walk
  attack
  cast
  hit
  death

Scale: models should be ~2 units tall (matching DungeonMap.TILE_SIZE = 3.0).
  - Mixamo exports (cm scale): set MODEL_SCALE = 0.02 in CharacterManager
  - Blender default (m scale, 2m tall): MODEL_SCALE = 1.0 (default)

Facing: models should face +Z in their local space. CharacterManager applies a
180-degree Y rotation so they align with the entity's facing direction.

If a file is missing, CharacterManager falls back to the box-man placeholder.
