# Synthetic macOS outputs

Written by hand from the formats of macOS 27 tools (`ps -axww -o pid=,ppid=,rss=,etime=,lstart=,command=`,
`sysctl`, `vm_stat`, `launchctl list`) and of an App Intents `extract.actionsdata` file, not captured from a Mac:
real process lists carry paths and arguments that are not ours to commit. `extract.actionsdata` holds one parameter
of every value kind seen on the Mac this was written on, plus one kind it has never seen.
