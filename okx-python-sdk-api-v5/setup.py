#!/usr/bin/env python3
from setuptools import setup

setup(
    name='okx-python',
    version='1.1.0',
    packages=['okx'],
    install_requires=['requests', 'websockets>=6.0']
)