#!/usr/bin/env python3
from setuptools import setup

setup(
    name='okex-api-v5',
    version='1.1.0',
    packages=['okx'],
    install_requires=['requests', 'websockets>=6.0']
)