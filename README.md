![Corda](https://www.corda.net/wp-content/uploads/2016/11/fg005_corda_b.png)

# Corda

Corda is a decentralised database system in which nodes trust each other as little as possible.

## Features

* A P2P network of nodes
* Smart contracts
* Flow framework
* "Notary" infrastructure to validate uniqueness of transactions
* Written as a platform for distributed apps called CorDapps
* Written in [Kotlin](https://kotlinlang.org), targeting the JVM
 
Read our full and planned feature list [here](https://docs.corda.net/inthebox.html).

## Getting started

Firstly, read the [Getting started](https://docs.corda.net/getting-set-up.html) documentation. 

To build your own CorDapps:

1. Clone the [CorDapp Template repository](https://github.com/corda/cordapp-template)
2. Read the [README](https://github.com/corda/cordapp-template/blob/master/README.md) (**IMPORTANT!**)
3. Read the [Writing a CorDapp](https://docs.corda.net/tutorial-cordapp.html) documentation

To look at the Corda source and run some sample applications:

1. Clone this repository
2. To run some sample CorDapps, read the [running the demos documentation](https://docs.corda.r3cev.com/running-the-demos.html)
3. Start hacking and [contribute](./CONTRIBUTING.md)!

## Useful links

* [Project website](https://corda.net)
* [Documentation](https://docs.corda.net)
* [Forum](https://discourse.corda.net)

## Development State
 
Corda is currently in very early development and should not be used in production systems. Breaking
changes will happen on minor versions until 1.0. Experimentation with Corda is recommended.

Pull requests, experiments, and contributions are encouraged and welcomed.

## Background

The project is supported by R3, a financial industry consortium, which is why it 
contains some code for financial use cases and why the documentation focuses on finance. The goal is to use it
to construct a global ledger, simplifying finance and reducing the overheads of banking. But it is run as 
an open source project and the basic technology of a peer-to-peer decentralised database may be useful 
for many different projects.

## Contributing

Please read [here](./CONTRIBUTING.md).

## License

[Apache 2.0](./LICENCE)
