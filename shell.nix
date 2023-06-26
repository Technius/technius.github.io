{ pkgs ? import <nixpkgs> {} }:

pkgs.mkShell {
  buildInputs = with pkgs; [
    (python3.withPackages (p: [ p.pip ]))
    python3.pkgs.venvShellHook
  ];
  venvDir = "./.venv";
  shellHook = ''
    runHook venvShellHook
    pip install -e .
  '';
}
